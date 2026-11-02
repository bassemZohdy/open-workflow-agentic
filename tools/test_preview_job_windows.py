import sys
import types
import unittest
from unittest import mock

from preview_job_windows import NativeProcess


class NativeProcessTerminationTest(unittest.TestCase):
    def test_access_denied_after_process_has_exited_is_a_completed_termination(self):
        api = types.SimpleNamespace(WAIT_OBJECT_0=0, WAIT_TIMEOUT=258)
        api.WaitForSingleObject = mock.Mock(side_effect=[api.WAIT_TIMEOUT, api.WAIT_OBJECT_0])
        api.TerminateProcess = mock.Mock(side_effect=PermissionError('termination raced with exit'))
        api.GetExitCodeProcess = mock.Mock(return_value=1)
        api.CloseHandle = mock.Mock()
        with mock.patch.dict(sys.modules, {'_winapi': api}):
            process = NativeProcess(123, 42, {})
            try:
                process.terminate()
                self.assertEqual(1, process.returncode)
                api.WaitForSingleObject.assert_any_call(123, 1000)
            finally:
                process.close()

    def test_access_denied_for_a_still_running_process_remains_an_error(self):
        api = types.SimpleNamespace(WAIT_OBJECT_0=0, WAIT_TIMEOUT=258)
        api.WaitForSingleObject = mock.Mock(return_value=api.WAIT_TIMEOUT)
        api.TerminateProcess = mock.Mock(side_effect=PermissionError('still running'))
        api.GetExitCodeProcess = mock.Mock()
        api.CloseHandle = mock.Mock()
        with mock.patch.dict(sys.modules, {'_winapi': api}):
            process = NativeProcess(123, 42, {})
            try:
                with self.assertRaises(PermissionError):
                    process.terminate()
                api.GetExitCodeProcess.assert_not_called()
            finally:
                process.close()


if __name__ == '__main__':
    unittest.main()
