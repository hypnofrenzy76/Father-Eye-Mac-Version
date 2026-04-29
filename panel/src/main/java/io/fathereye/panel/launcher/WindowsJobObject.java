package io.fathereye.panel.launcher;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/**
 * Pnl-51 (2026-04-26): Win32 Job Object wrapper guaranteeing that
 * spawned server JVMs cannot survive the panel that started them.
 *
 * <p>Pnl-18 had this deferred ("Hard TerminateProcess / End-process-tree
 * still bypasses [the shutdown hook]; needs Win32 Job Object, deferred").
 * The user reported orphan server JVMs surviving panel kills via
 * TaskKill /F: "we have to be certain there are never any orphans".
 *
 * <p>Mechanism: a Job Object configured with
 * {@code JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE} terminates every process
 * assigned to it the moment the job handle closes. The handle is
 * owned by the panel JVM, so when the panel dies for ANY reason --
 * graceful exit, JVM crash, TaskKill /F, OS reboot, BSOD -- the
 * kernel itself reaps the children. Unlike {@link Runtime#addShutdownHook},
 * this needs no cooperation from the panel and cannot be bypassed.
 *
 * <p>This helper is Windows-only. {@link #isSupported()} returns
 * false on other platforms; callers should no-op gracefully there.
 *
 * <p>Usage:
 * <pre>
 *   WindowsJobObject job = WindowsJobObject.create();
 *   long pid = process.pid();
 *   job.assign(pid);
 *   // ... later, on launcher dispose:
 *   job.close();
 * </pre>
 *
 * <p>Idempotent: assigning twice or closing twice is harmless.
 */
public final class WindowsJobObject implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-JobObject");

    /** Force-kill all processes in the job when the last handle closes. */
    private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000;
    /** SetInformationJobObject info-class for the EXTENDED struct. */
    private static final int JobObjectExtendedLimitInformation = 9;
    /** Minimal access rights needed to assign a process to a job. */
    private static final int PROCESS_TERMINATE = 0x0001;
    private static final int PROCESS_SET_QUOTA = 0x0100;

    /** True when running on Windows. */
    public static boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
    }

    private final WinNT.HANDLE jobHandle;
    private boolean closed = false;

    private WindowsJobObject(WinNT.HANDLE handle) {
        this.jobHandle = handle;
    }

    /**
     * Create a new Job Object with KILL_ON_JOB_CLOSE configured.
     * Returns {@code null} on non-Windows or on any failure (caller
     * should treat null as "no job protection available" and proceed
     * without it -- the existing shutdown hook still gives a partial
     * safety net).
     */
    public static WindowsJobObject create() {
        if (!isSupported()) return null;
        try {
            WinNT.HANDLE h = JobKernel32.INSTANCE.CreateJobObjectW(null, null);
            if (h == null || h.equals(WinBase.INVALID_HANDLE_VALUE)) {
                LOG.warn("CreateJobObjectW failed (lastError={}); orphan protection disabled.",
                        JobKernel32.INSTANCE.GetLastError());
                return null;
            }
            JOBOBJECT_EXTENDED_LIMIT_INFORMATION info = new JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
            info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
            info.write();
            boolean ok = JobKernel32.INSTANCE.SetInformationJobObject(h,
                    JobObjectExtendedLimitInformation, info.getPointer(), info.size());
            if (!ok) {
                int err = JobKernel32.INSTANCE.GetLastError();
                LOG.warn("SetInformationJobObject failed (lastError={}); orphan protection disabled.", err);
                JobKernel32.INSTANCE.CloseHandle(h);
                return null;
            }
            LOG.info("Job Object created with KILL_ON_JOB_CLOSE; spawned server JVMs will be reaped on panel exit.");
            return new WindowsJobObject(h);
        } catch (Throwable t) {
            LOG.warn("Job Object setup threw; orphan protection disabled.", t);
            return null;
        }
    }

    /**
     * Assign the given PID to this job. After assignment, when this
     * job's handle closes (panel dies), the OS terminates that
     * process. Returns true on success, false on any failure (and
     * logs the cause).
     */
    public boolean assign(long pid) {
        if (closed) {
            LOG.warn("assign({}) called on a closed JobObject; ignored.", pid);
            return false;
        }
        WinNT.HANDLE proc = JobKernel32.INSTANCE.OpenProcess(
                PROCESS_TERMINATE | PROCESS_SET_QUOTA, false, (int) pid);
        if (proc == null || proc.equals(WinBase.INVALID_HANDLE_VALUE)) {
            int err = JobKernel32.INSTANCE.GetLastError();
            LOG.warn("OpenProcess({}) failed (lastError={}); cannot enforce no-orphan policy on this PID.", pid, err);
            return false;
        }
        try {
            boolean ok = JobKernel32.INSTANCE.AssignProcessToJobObject(jobHandle, proc);
            if (!ok) {
                int err = JobKernel32.INSTANCE.GetLastError();
                // Common causes:
                //   - 5 (ERROR_ACCESS_DENIED): process already in another job that
                //     doesn't allow children to break away. On Windows 8+ nested
                //     jobs are allowed by default, so this is rare.
                //   - 1 (ERROR_INVALID_FUNCTION): pre-Win8 host without nested-job
                //     support and the panel itself is in a job (e.g. Windows Sandbox).
                LOG.warn("AssignProcessToJobObject({}) failed (lastError={}); pid will not be auto-killed on panel exit.", pid, err);
                return false;
            }
            LOG.info("Server JVM pid={} assigned to Job Object; will be auto-killed on panel exit.", pid);
            return true;
        } finally {
            JobKernel32.INSTANCE.CloseHandle(proc);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            JobKernel32.INSTANCE.CloseHandle(jobHandle);
            LOG.info("Job Object closed; any assigned processes have been terminated by the OS.");
        } catch (Throwable t) {
            LOG.warn("Closing Job Object threw", t);
        }
    }

    // ---- JNA bindings ------------------------------------------------

    /**
     * JNA's bundled {@link com.sun.jna.platform.win32.Kernel32} omits
     * the Job Object family in 5.14, so we declare the four functions
     * we need against the same DLL. Char-set follows the rest of the
     * panel ({@link W32APIOptions#DEFAULT_OPTIONS} = UNICODE).
     */
    interface JobKernel32 extends StdCallLibrary {
        JobKernel32 INSTANCE = Native.load("kernel32", JobKernel32.class, W32APIOptions.DEFAULT_OPTIONS);
        WinNT.HANDLE CreateJobObjectW(WinBase.SECURITY_ATTRIBUTES sa, String name);
        boolean SetInformationJobObject(WinNT.HANDLE jobHandle, int jobObjectInformationClass, Pointer info, int cbInfoLength);
        boolean AssignProcessToJobObject(WinNT.HANDLE jobHandle, WinNT.HANDLE processHandle);
        boolean CloseHandle(WinNT.HANDLE handle);
        WinNT.HANDLE OpenProcess(int desiredAccess, boolean inheritHandle, int processId);
        int GetLastError();
    }

    public static class IO_COUNTERS extends Structure {
        public long ReadOperationCount;
        public long WriteOperationCount;
        public long OtherOperationCount;
        public long ReadTransferCount;
        public long WriteTransferCount;
        public long OtherTransferCount;
        @Override protected List<String> getFieldOrder() {
            return Arrays.asList("ReadOperationCount", "WriteOperationCount", "OtherOperationCount",
                    "ReadTransferCount", "WriteTransferCount", "OtherTransferCount");
        }
    }

    public static class JOBOBJECT_BASIC_LIMIT_INFORMATION extends Structure {
        public WinNT.LARGE_INTEGER PerProcessUserTimeLimit = new WinNT.LARGE_INTEGER(0);
        public WinNT.LARGE_INTEGER PerJobUserTimeLimit = new WinNT.LARGE_INTEGER(0);
        public int LimitFlags;
        public BaseTSD.SIZE_T MinimumWorkingSetSize = new BaseTSD.SIZE_T(0);
        public BaseTSD.SIZE_T MaximumWorkingSetSize = new BaseTSD.SIZE_T(0);
        public int ActiveProcessLimit;
        public BaseTSD.ULONG_PTR Affinity = new BaseTSD.ULONG_PTR(0);
        public int PriorityClass;
        public int SchedulingClass;
        @Override protected List<String> getFieldOrder() {
            return Arrays.asList("PerProcessUserTimeLimit", "PerJobUserTimeLimit", "LimitFlags",
                    "MinimumWorkingSetSize", "MaximumWorkingSetSize", "ActiveProcessLimit",
                    "Affinity", "PriorityClass", "SchedulingClass");
        }
    }

    public static class JOBOBJECT_EXTENDED_LIMIT_INFORMATION extends Structure {
        public JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation = new JOBOBJECT_BASIC_LIMIT_INFORMATION();
        public IO_COUNTERS IoInfo = new IO_COUNTERS();
        public BaseTSD.SIZE_T ProcessMemoryLimit = new BaseTSD.SIZE_T(0);
        public BaseTSD.SIZE_T JobMemoryLimit = new BaseTSD.SIZE_T(0);
        public BaseTSD.SIZE_T PeakProcessMemoryUsed = new BaseTSD.SIZE_T(0);
        public BaseTSD.SIZE_T PeakJobMemoryUsed = new BaseTSD.SIZE_T(0);
        @Override protected List<String> getFieldOrder() {
            return Arrays.asList("BasicLimitInformation", "IoInfo", "ProcessMemoryLimit",
                    "JobMemoryLimit", "PeakProcessMemoryUsed", "PeakJobMemoryUsed");
        }
    }
}
