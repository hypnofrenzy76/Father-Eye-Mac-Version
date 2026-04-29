package io.fathereye.bridge.ipc;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinBase.OVERLAPPED;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.W32APIOptions;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JNA wrapper for the bridge's named-pipe server endpoint with OVERLAPPED
 * (asynchronous) I/O.
 *
 * <p><b>Why overlapped is mandatory.</b> A synchronous (default) named-pipe
 * handle on Windows serialises read and write inside the kernel — when the
 * Publisher thread is parked in WriteFile waiting for the panel to drain
 * the outgoing buffer, the IpcSession reader thread's ReadFile (and any
 * other writer) on the same handle deadlocks even though both directions
 * have plenty of buffer space. This was the root cause of "panel hung
 * after handshake": the bridge's first tps_topic snapshot publish wedged
 * inside WriteFile, holding the IpcSession monitor, while the panel's
 * IPC thread tried to send a second Subscribe — both ends deadlocked
 * symmetrically.
 *
 * <p>Each operation issues an OVERLAPPED struct, parks the calling thread
 * on a per-direction event, and resolves via GetOverlappedResult. The
 * events are reused across calls; the caller serialises concurrent reads
 * (only one IpcSession reader) and concurrent writes (Publisher uses
 * synchronized sendJson) externally.
 */
public final class NamedPipeServer {

    /** JNA's bundled Kernel32 omits GetOverlappedResult in 5.14 — declare it. */
    private interface Kernel32Ex extends Kernel32 {
        Kernel32Ex EX = Native.load("kernel32", Kernel32Ex.class, W32APIOptions.DEFAULT_OPTIONS);
        boolean GetOverlappedResult(HANDLE hFile, OVERLAPPED lpOverlapped,
                                    IntByReference lpNumberOfBytesTransferred, boolean bWait);
    }

    private static final int BUFFER_BYTES = 256 * 1024;
    private static final int FILE_FLAG_FIRST_PIPE_INSTANCE = 0x00080000;
    private static final int ERROR_PIPE_CONNECTED = 535;

    private final String pipeName;
    private HANDLE handle = WinBase.INVALID_HANDLE_VALUE;
    private HANDLE readEvent = WinBase.INVALID_HANDLE_VALUE;
    private HANDLE writeEvent = WinBase.INVALID_HANDLE_VALUE;
    private HANDLE connectEvent = WinBase.INVALID_HANDLE_VALUE;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public NamedPipeServer(String pipeName) {
        this.pipeName = pipeName;
    }

    public void create(boolean firstInstance) throws IOException {
        // FILE_FLAG_OVERLAPPED on the SERVER end mirrors the client; without
        // it the kernel I/O queue serialises reads and writes (see class
        // doc).
        int openMode = WinBase.PIPE_ACCESS_DUPLEX | WinNT.FILE_FLAG_OVERLAPPED;
        if (firstInstance) openMode |= FILE_FLAG_FIRST_PIPE_INSTANCE;
        int pipeMode = WinBase.PIPE_TYPE_BYTE | WinBase.PIPE_READMODE_BYTE | WinBase.PIPE_WAIT;
        handle = Kernel32.INSTANCE.CreateNamedPipe(
                pipeName,
                openMode,
                pipeMode,
                1,
                BUFFER_BYTES,
                BUFFER_BYTES,
                0,
                null);
        if (handle == null || WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
            int err = Kernel32.INSTANCE.GetLastError();
            throw new IOException("CreateNamedPipe failed for " + pipeName + " (winerr=" + err + ")");
        }
        readEvent = Kernel32.INSTANCE.CreateEvent(null, true, false, null);
        writeEvent = Kernel32.INSTANCE.CreateEvent(null, true, false, null);
        connectEvent = Kernel32.INSTANCE.CreateEvent(null, true, false, null);
        if (readEvent == null || WinBase.INVALID_HANDLE_VALUE.equals(readEvent)
                || writeEvent == null || WinBase.INVALID_HANDLE_VALUE.equals(writeEvent)
                || connectEvent == null || WinBase.INVALID_HANDLE_VALUE.equals(connectEvent)) {
            throw new IOException("CreateEvent failed for pipe " + pipeName);
        }
    }

    public void connect() throws IOException {
        OVERLAPPED ov = new OVERLAPPED();
        ov.hEvent = connectEvent;
        ov.write();
        ov.setAutoWrite(false);
        Kernel32.INSTANCE.ResetEvent(connectEvent);
        boolean ok = Kernel32.INSTANCE.ConnectNamedPipe(handle, ov);
        if (ok) return; // immediate success (rare, but documented)
        int err = Kernel32.INSTANCE.GetLastError();
        if (err == ERROR_PIPE_CONNECTED) return; // client connected before our call
        if (err != WinError.ERROR_IO_PENDING) {
            throw new IOException("ConnectNamedPipe failed (winerr=" + err + ")");
        }
        int wait = Kernel32.INSTANCE.WaitForSingleObject(connectEvent, WinBase.INFINITE);
        if (wait != WinBase.WAIT_OBJECT_0) {
            throw new IOException("WaitForSingleObject(connect) returned " + wait);
        }
        IntByReference dummy = new IntByReference();
        boolean got = Kernel32Ex.EX.GetOverlappedResult(handle, ov, dummy, true);
        if (!got) {
            int err2 = Kernel32.INSTANCE.GetLastError();
            if (err2 != ERROR_PIPE_CONNECTED) {
                throw new IOException("GetOverlappedResult(connect) failed (winerr=" + err2 + ")");
            }
        }
    }

    public byte[] readExact(int n) throws IOException {
        if (n <= 0) return new byte[0];
        byte[] out = new byte[n];
        int total = 0;
        while (total < n) {
            int want = n - total;
            byte[] tmp = new byte[want];
            int got = overlappedRead(tmp, want);
            if (got <= 0) {
                throw new IOException("ReadFile short/error after " + total + " bytes");
            }
            System.arraycopy(tmp, 0, out, total, got);
            total += got;
        }
        return out;
    }

    public void writeAll(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) return;
        int gone = overlappedWrite(bytes, bytes.length);
        if (gone != bytes.length) {
            throw new IOException("WriteFile incomplete (" + gone + "/" + bytes.length + ")");
        }
    }

    /**
     * Per MSDN, when OVERLAPPED is supplied, lpNumberOfBytesRead/Written
     * MUST be NULL — the byte count comes from GetOverlappedResult only.
     * Passing a non-null IntByReference there silently returns 0, which
     * caused the "ReadFile short/error after 0 bytes" handshake failures.
     */
    /**
     * JNA Structures auto-write Java fields to native before every call,
     * which clobbers the kernel-populated Internal/InternalHigh fields
     * between ReadFile/WriteFile and GetOverlappedResult. setAutoWrite(false)
     * after the initial hEvent push freezes the struct so the kernel's
     * writes survive across the JNA boundary.
     */
    private int overlappedRead(byte[] buf, int len) throws IOException {
        OVERLAPPED ov = new OVERLAPPED();
        ov.hEvent = readEvent;
        ov.write();
        ov.setAutoWrite(false);
        Kernel32.INSTANCE.ResetEvent(readEvent);
        boolean ok = Kernel32.INSTANCE.ReadFile(handle, buf, len, null, ov);
        if (!ok) {
            int err = Kernel32.INSTANCE.GetLastError();
            if (err != WinError.ERROR_IO_PENDING) {
                throw new IOException("ReadFile failed (winerr=" + err + ")");
            }
            int wait = Kernel32.INSTANCE.WaitForSingleObject(readEvent, WinBase.INFINITE);
            if (wait != WinBase.WAIT_OBJECT_0) {
                throw new IOException("WaitForSingleObject(read) returned " + wait);
            }
        }
        IntByReference read = new IntByReference();
        boolean got = Kernel32Ex.EX.GetOverlappedResult(handle, ov, read, true);
        if (!got) {
            int err2 = Kernel32.INSTANCE.GetLastError();
            throw new IOException("GetOverlappedResult(read) failed (winerr=" + err2 + ")");
        }
        return read.getValue();
    }

    private int overlappedWrite(byte[] buf, int len) throws IOException {
        OVERLAPPED ov = new OVERLAPPED();
        ov.hEvent = writeEvent;
        ov.write();
        ov.setAutoWrite(false);
        Kernel32.INSTANCE.ResetEvent(writeEvent);
        boolean ok = Kernel32.INSTANCE.WriteFile(handle, buf, len, null, ov);
        if (!ok) {
            int err = Kernel32.INSTANCE.GetLastError();
            if (err != WinError.ERROR_IO_PENDING) {
                throw new IOException("WriteFile failed (winerr=" + err + ")");
            }
            int wait = Kernel32.INSTANCE.WaitForSingleObject(writeEvent, WinBase.INFINITE);
            if (wait != WinBase.WAIT_OBJECT_0) {
                throw new IOException("WaitForSingleObject(write) returned " + wait);
            }
        }
        IntByReference written = new IntByReference();
        boolean got = Kernel32Ex.EX.GetOverlappedResult(handle, ov, written, true);
        if (!got) {
            int err2 = Kernel32.INSTANCE.GetLastError();
            throw new IOException("GetOverlappedResult(write) failed (winerr=" + err2 + ")");
        }
        return written.getValue();
    }

    public void disconnect() {
        if (handle != null && !WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
            Kernel32.INSTANCE.DisconnectNamedPipe(handle);
        }
    }

    public void close() {
        if (closed.compareAndSet(false, true)) {
            disconnect();
            if (handle != null && !WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
                Kernel32.INSTANCE.CloseHandle(handle);
                handle = WinBase.INVALID_HANDLE_VALUE;
            }
            if (readEvent != null && !WinBase.INVALID_HANDLE_VALUE.equals(readEvent)) {
                Kernel32.INSTANCE.CloseHandle(readEvent);
                readEvent = WinBase.INVALID_HANDLE_VALUE;
            }
            if (writeEvent != null && !WinBase.INVALID_HANDLE_VALUE.equals(writeEvent)) {
                Kernel32.INSTANCE.CloseHandle(writeEvent);
                writeEvent = WinBase.INVALID_HANDLE_VALUE;
            }
            if (connectEvent != null && !WinBase.INVALID_HANDLE_VALUE.equals(connectEvent)) {
                Kernel32.INSTANCE.CloseHandle(connectEvent);
                connectEvent = WinBase.INVALID_HANDLE_VALUE;
            }
        }
    }

    public boolean isClosed() { return closed.get(); }

    public String pipeName() { return pipeName; }
}
