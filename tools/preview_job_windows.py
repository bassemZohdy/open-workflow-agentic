"""Own a Windows preview process family, including plugin grandchildren. Not an untrusted-process sandbox."""
import ctypes
import os
from pathlib import Path
import json
import subprocess
import sys
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
        # A trusted launcher waits for stdin before creating the target. Association therefore
        # happens BEFORE even a fast target can create descendants or exit.
        options.pop('stdin', None)
        options['creationflags'] = subprocess.CREATE_NO_WINDOW
        process = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), '--owned-launch', json.dumps(arguments)],
                                   stdin=subprocess.PIPE, **options)
        try:
            if not self.kernel.AssignProcessToJobObject(self.handle, wintypes.HANDLE(int(process._handle))):
                raise ctypes.WinError(ctypes.get_last_error())
            process.stdin.write('go\n' if options.get('text') else b'go\n')
            process.stdin.flush(); process.stdin.close()
            return process
        except BaseException:
            if process.poll() is None:
                process.terminate(); process.wait(timeout=10)
            raise

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


if __name__ == '__main__':
    if os.name != 'nt' or len(sys.argv) != 3 or sys.argv[1] != '--owned-launch':
        sys.exit('Internal Windows preview launcher only')
    if sys.stdin.readline() != 'go\n':
        sys.exit(2)
    target = subprocess.Popen(json.loads(sys.argv[2]), stdin=subprocess.DEVNULL,
                              creationflags=subprocess.CREATE_NO_WINDOW)
    sys.exit(target.wait())
