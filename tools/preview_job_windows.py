"""Own a Windows preview process family, including plugin grandchildren. Not an untrusted-process sandbox."""
import ctypes
import os
import subprocess
import time
from ctypes import wintypes


class OwnedJob:
    def __init__(self):
        if os.name != 'nt':
            raise RuntimeError('The native preview process-family fixture requires Windows')
        class Basic(ctypes.Structure):
            _fields_ = [('processTime', ctypes.c_longlong), ('jobTime', ctypes.c_longlong),
                        ('flags', wintypes.DWORD), ('minimumWorkingSet', ctypes.c_size_t),
                        ('maximumWorkingSet', ctypes.c_size_t), ('activeLimit', wintypes.DWORD),
                        ('affinity', ctypes.c_size_t), ('priority', wintypes.DWORD), ('scheduling', wintypes.DWORD)]
        class Counters(ctypes.Structure):
            _fields_ = [(name, ctypes.c_ulonglong) for name in
                        ('readOps', 'writeOps', 'otherOps', 'readBytes', 'writeBytes', 'otherBytes')]
        class Extended(ctypes.Structure):
            _fields_ = [('basic', Basic), ('io', Counters), ('processMemory', ctypes.c_size_t),
                        ('jobMemory', ctypes.c_size_t), ('peakProcessMemory', ctypes.c_size_t),
                        ('peakJobMemory', ctypes.c_size_t)]
        self.kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        signatures = {
            'CreateJobObjectW': ([ctypes.c_void_p, wintypes.LPCWSTR], wintypes.HANDLE),
            'SetInformationJobObject': ([wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD], wintypes.BOOL),
            'AssignProcessToJobObject': ([wintypes.HANDLE, wintypes.HANDLE], wintypes.BOOL),
            'QueryInformationJobObject': ([wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD,
                                           ctypes.POINTER(wintypes.DWORD)], wintypes.BOOL),
            'TerminateJobObject': ([wintypes.HANDLE, wintypes.UINT], wintypes.BOOL),
            'CloseHandle': ([wintypes.HANDLE], wintypes.BOOL),
        }
        for name, (arguments, result) in signatures.items():
            method = getattr(self.kernel, name); method.argtypes = arguments; method.restype = result
        self.handle = self.kernel.CreateJobObjectW(None, None)  # Unnamed, private handle; never an existing user's job.
        if not self.handle:
            raise ctypes.WinError(ctypes.get_last_error())
        self.summary = None
        limits = Extended(); limits.basic.flags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE; no breakaway flags.
        if not self.kernel.SetInformationJobObject(self.handle, 9, ctypes.byref(limits), ctypes.sizeof(limits)):
            error = ctypes.WinError(ctypes.get_last_error()); self.kernel.CloseHandle(self.handle); self.handle = None
            raise error
        actual = Extended()
        if not self.kernel.QueryInformationJobObject(self.handle, 9, ctypes.byref(actual), ctypes.sizeof(actual), None) or not actual.basic.flags & 0x2000:
            self.kernel.CloseHandle(self.handle); self.handle = None
            raise RuntimeError('Kernel kill-on-close configuration was not verified')

    def start(self, arguments, **options):
        if not self.handle:
            raise RuntimeError('Cannot launch into a closed preview job')
        # Create the native target suspended. Assign its actual process handle before
        # resuming its primary thread: no launcher/alias descendant inheritance assumption.
        import _winapi
        import msvcrt
        streams = []
        readers = {}
        def stream(value, name):
            if value == subprocess.PIPE:
                read, write = os.pipe()
                readers[name] = os.fdopen(read, 'r' if options.get('text') else 'rb')
                opened = os.fdopen(write, 'wb')
                streams.append(opened)
            elif value is None or value == subprocess.DEVNULL:
                opened = open(os.devnull, 'rb' if name == 'stdin' else 'wb'); streams.append(opened)
            else:
                opened = value
            return msvcrt.get_osfhandle(opened.fileno())
        handles = []
        process = None; thread = None
        try:
            startup = subprocess.STARTUPINFO()
            startup.dwFlags = subprocess.STARTF_USESTDHANDLES
            startup.hStdInput = stream(subprocess.DEVNULL, 'stdin')
            startup.hStdOutput = stream(options.get('stdout'), 'stdout')
            startup.hStdError = startup.hStdOutput if options.get('stderr') == subprocess.STDOUT else stream(options.get('stderr'), 'stderr')
            handles = list(set([startup.hStdInput, startup.hStdOutput, startup.hStdError]))
            for handle in handles:os.set_handle_inheritable(handle, True)
            startup.lpAttributeList = {'handle_list': handles}
            handle, thread, pid, _ = _winapi.CreateProcess(str(arguments[0]), subprocess.list2cmdline(arguments),
                None, None, True, subprocess.CREATE_NO_WINDOW | 0x4, options.get('env'),
                str(options['cwd']) if options.get('cwd') else None, startup)
            process = NativeProcess(handle, pid, readers)
            if not self.kernel.AssignProcessToJobObject(self.handle, wintypes.HANDLE(handle)):
                raise ctypes.WinError(ctypes.get_last_error())
            resume = self.kernel.ResumeThread
            resume.argtypes = [wintypes.HANDLE]; resume.restype = wintypes.DWORD
            if resume(wintypes.HANDLE(thread)) == 0xffffffff:
                raise ctypes.WinError(ctypes.get_last_error())
            return process
        except BaseException:
            if process and process.poll() is None:
                process.terminate(); process.wait(timeout=10)
            for reader in readers.values():reader.close()
            raise
        finally:
            if thread is not None:_winapi.CloseHandle(thread)
            for handle in handles:os.set_handle_inheritable(handle, False)
            for opened in streams:opened.close()

    def members(self):
        if not self.handle:
            raise RuntimeError('Cannot inspect a closed job')
        capacity = 1024
        buffer = ctypes.create_string_buffer(8 + capacity * ctypes.sizeof(ctypes.c_size_t))
        if not self.kernel.QueryInformationJobObject(self.handle, 3, buffer, len(buffer), None):
            raise ctypes.WinError(ctypes.get_last_error())
        assigned = wintypes.DWORD.from_buffer(buffer, 0).value
        count = wintypes.DWORD.from_buffer(buffer, 4).value
        if assigned != count or count > capacity:
            raise RuntimeError('Incomplete preview process-family inventory')
        return list((ctypes.c_size_t * count).from_buffer(buffer, 8))

    def close(self):
        if not self.handle:
            return self.summary
        try:
            before = self.members()
            if not self.kernel.TerminateJobObject(self.handle, 0):
                raise ctypes.WinError(ctypes.get_last_error())
            deadline = time.monotonic() + 10
            remaining = self.members()
            while remaining and time.monotonic() < deadline:
                time.sleep(.05); remaining = self.members()
            if remaining:
                raise RuntimeError('Owned preview descendants did not become terminal')
            self.summary = {'membersBeforeStop': before, 'membersAfterStop': remaining, 'killOnClose': True}
            return self.summary
        finally:
            # Kernel kill-on-close also covers controller failure; the handle is not inherited by children.
            self.kernel.CloseHandle(self.handle); self.handle = None


class NativeProcess:
    """The small process-handle interface needed by this owned preview."""
    def __init__(self, handle, pid, readers):
        import _winapi
        self._api = _winapi
        self._handle = handle; self.pid = pid; self.returncode = None
        self.stdout = readers.get('stdout'); self.stderr = readers.get('stderr')

    def poll(self):
        import _winapi
        if self.returncode is None and _winapi.WaitForSingleObject(self._handle, 0) == _winapi.WAIT_OBJECT_0:
            self.returncode = _winapi.GetExitCodeProcess(self._handle)
        return self.returncode

    def wait(self, timeout=None):
        import _winapi
        if self.returncode is None:
            result = _winapi.WaitForSingleObject(self._handle, _winapi.INFINITE if timeout is None else int(timeout * 1000))
            if result == _winapi.WAIT_TIMEOUT:raise subprocess.TimeoutExpired('owned preview', timeout)
            if result != _winapi.WAIT_OBJECT_0:raise RuntimeError('Owned native process wait failed')
            self.returncode = _winapi.GetExitCodeProcess(self._handle)
        return self.returncode

    def terminate(self):
        import _winapi
        if self.poll() is None:_winapi.TerminateProcess(self._handle, 1)

    kill = terminate

    def close(self):
        if self._handle:
            self._api.CloseHandle(self._handle); self._handle = None
        for stream in (self.stdout, self.stderr):
            if stream:stream.close()

    def __del__(self):
        if self._handle:self._api.CloseHandle(self._handle); self._handle = None
