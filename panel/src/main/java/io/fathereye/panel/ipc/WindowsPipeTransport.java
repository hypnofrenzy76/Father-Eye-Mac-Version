package io.fathereye.panel.ipc;

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
 * Windows named pipe {@link Transport} backed by JNA Kernel32.
 *
 * <p><b>Overlapped (asynchronous) I/O.</b> The pipe is opened with
 * {@code FILE_FLAG_OVERLAPPED} so concurrent {@link #readExact} and
 * {@link #writeAll} from different threads do not deadlock. Synchronous
 * (default) named-pipe handles on Windows serialise read and write inside
 * the kernel — when one thread parks in {@code ReadFile} waiting for inbound
 * bytes, another thread's {@code WriteFile} on the same handle blocks
 * forever even though the outbound buffer has plenty of space. That
 * deadlock was the original cause of "panel hung after handshake": the
 * IPC thread's second {@code Subscribe} sendJson wedged inside WriteFile
 * because the PipeReader thread was already parked in ReadFile.
 *
 * <p>Each call below issues an OVERLAPPED operation, parks the calling
 * thread on a per-call event, then resolves the result via
 * {@code GetOverlappedResult}. The events are per-direction (one for read,
 * one for write) and reused across calls — readers serialise via the
 * caller's external readLock, writers via writeLock.
 */
public final class WindowsPipeTransport implements Transport {

    /** JNA's bundled Kernel32 omits GetOverlappedResult in 5.14 — declare it. */
    private interface Kernel32Ex extends Kernel32 {
        Kernel32Ex EX = Native.load("kernel32", Kernel32Ex.class, W32APIOptions.DEFAULT_OPTIONS);
        boolean GetOverlappedResult(HANDLE hFile, OVERLAPPED lpOverlapped,
                                    IntByReference lpNumberOfBytesTransferred, boolean bWait);
    }

    private static final int ERROR_PIPE_BUSY = 231;

    private final String pipeName;
    private HANDLE handle = WinBase.INVALID_HANDLE_VALUE;
    private HANDLE readEvent = WinBase.INVALID_HANDLE_VALUE;
    private HANDLE writeEvent = WinBase.INVALID_HANDLE_VALUE;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public WindowsPipeTransport(String pipeName) {
        this.pipeName = pipeName;
    }

    @Override public void connect() throws IOException {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (true) {
            handle = Kernel32.INSTANCE.CreateFile(
                    pipeName,
                    WinNT.GENERIC_READ | WinNT.GENERIC_WRITE,
                    0, null, WinNT.OPEN_EXISTING,
                    WinNT.FILE_FLAG_OVERLAPPED,    // <-- crucial: allows concurrent read+write
                    null);
            if (handle != null && !WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
                // Per-direction reusable manual-reset events. ResetEvent
                // before each issue, WaitForSingleObject after.
                readEvent = Kernel32.INSTANCE.CreateEvent(null, true, false, null);
                writeEvent = Kernel32.INSTANCE.CreateEvent(null, true, false, null);
                if (readEvent == null || WinBase.INVALID_HANDLE_VALUE.equals(readEvent)
                        || writeEvent == null || WinBase.INVALID_HANDLE_VALUE.equals(writeEvent)) {
                    throw new IOException("CreateEvent failed for pipe " + pipeName);
                }
                return;
            }
            int err = Kernel32.INSTANCE.GetLastError();
            if (err == ERROR_PIPE_BUSY && System.currentTimeMillis() < deadline) {
                Kernel32.INSTANCE.WaitNamedPipe(pipeName, 1000);
                continue;
            }
            throw new IOException("CreateFile failed for " + pipeName + " (winerr=" + err + ")");
        }
    }

    @Override public byte[] readExact(int n) throws IOException {
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

    @Override public void writeAll(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) return;
        int gone = overlappedWrite(bytes, bytes.length);
        if (gone != bytes.length) {
            throw new IOException("WriteFile incomplete (" + gone + "/" + bytes.length + ")");
        }
    }

    /**
     * Issue an OVERLAPPED ReadFile, park on readEvent, return bytes-read.
     * Throws IOException on pipe break.
     *
     * <p>Per MSDN, when using OVERLAPPED I/O the {@code lpNumberOfBytesRead}
     * parameter to ReadFile/WriteFile must be NULL — the actual byte count
     * is fetched via {@code GetOverlappedResult} regardless of whether the
     * call returned synchronously or asynchronously. Passing a non-null
     * IntByReference there returns 0 (undocumented behavior) and was the
     * cause of the "ReadFile short/error after 0 bytes" failures during
     * handshake.
     */
    /**
     * JNA Structures auto-write Java fields to native memory before each
     * call, which would overwrite the kernel-populated {@code Internal} /
     * {@code InternalHigh} fields between ReadFile and GetOverlappedResult.
     * We disable auto-write after setting hEvent so the kernel's writes
     * survive across the second JNA call. {@code ov.read()} explicitly
     * pulls native → Java so the result is also visible if a caller ever
     * inspects the struct.
     */
    private int overlappedRead(byte[] buf, int len) throws IOException {
        OVERLAPPED ov = new OVERLAPPED();
        ov.hEvent = readEvent;
        ov.write();              // push hEvent to native
        ov.setAutoWrite(false);  // freeze: future calls won't clobber kernel fields
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

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
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
        }
    }

    @Override public boolean isClosed() { return closed.get(); }
    @Override public String address() { return pipeName; }
}
