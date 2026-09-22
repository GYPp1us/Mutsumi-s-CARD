"""核验并整理四个同版本、同签名的安装包及校验和。"""
import hashlib
import os
import re
import shutil
import subprocess
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
from verify_native_libraries import verify

ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")
BASELINE = 250_882_883  # v0.8.0 GitHub Release 通用包实测字节数

def prepare():
    destination = Path("release")
    destination.mkdir(exist_ok=True)
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0"
    expected = Path("release-signing-cert.sha256").read_text().strip().lower()
    version = os.environ["RELEASE_VERSION"].removeprefix("v")
    version_code = re.search(r"versionCode = (\d+)", Path("app/build.gradle.kts").read_text()).group(1)
    report = ["# 安装包体积", "", "全部包版本和签名一致，可覆盖历史正式版。通用包供旧更新器使用；新更新器优先下载设备对应架构。", "", "| 安装包 | 字节 | MiB | 相比 0.8.0 通用包减少 |", "| --- | ---: | ---: | ---: |"]
    for abi in (*ABIS, "universal"):
        apk = Path(f"app/build/outputs/apk/release/app-{abi}-release.apk")
        verify(apk, set(ABIS) if abi == "universal" else {abi})
        with ZipFile(apk) as archive:
            for entry in archive.infolist():
                if entry.filename.endswith(".so"):
                    if entry.compress_type != ZIP_DEFLATED:
                        raise ValueError(f"原生库未压缩：{entry.filename}")
        cert = subprocess.check_output([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)], text=True)
        if f"Signer #1 certificate SHA-256 digest: {expected}" not in cert:
            raise ValueError(f"历史签名不匹配：{apk}")
        badging = subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(apk)], text=True)
        if f"versionName='{version}'" not in badging or f"versionCode='{version_code}'" not in badging or "package: name='com.mutsumi.card'" not in badging:
            raise ValueError(f"版本或应用 ID 不匹配：{apk}")
        name = "mutsumi-card-release.apk" if abi == "universal" else f"mutsumi-card-{abi}-release.apk"
        output = destination / name
        shutil.copyfile(apk, output)
        with output.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        (destination / f"{name}.sha256").write_text(f"{digest}  {name}\n")
        size = output.stat().st_size
        if size >= BASELINE:
            raise ValueError(f"包体未缩小：{name}")
        report.append(f"| {name} | {size:,} | {size / 1024**2:.2f} | {1 - size / BASELINE:.1%} |")
    (destination / "包体报告.md").write_text("\n".join(report) + "\n", encoding="utf-8")
    print("\n".join(report))

if __name__ == "__main__":
    prepare()
