"""防止按架构打包时遗漏 JNI 或混入不支持的架构。"""
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile
from verify_native_libraries import verify

class NativeLibrariesTest(unittest.TestCase):
    def apk(self, directory, paths):
        apk = Path(directory) / "test.apk"
        with ZipFile(apk, "w") as archive:
            for path in paths:
                archive.writestr(path, b"test")
        return apk

    def test_single_architecture_is_complete(self):
        with tempfile.TemporaryDirectory() as directory:
            verify(self.apk(directory, ["lib/arm64-v8a/libmutsumi_md2svg.so"]), {"arm64-v8a"})

    def test_missing_renderer_and_unexpected_architecture_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                verify(self.apk(directory, ["lib/x86_64/libother.so"]), {"x86_64"})
            with self.assertRaises(ValueError):
                verify(self.apk(directory, ["lib/x86_64/libmutsumi_md2svg.so", "lib/x86/libother.so"]), {"x86_64"})

if __name__ == "__main__":
    unittest.main()
