"""校验 APK 声明的每个架构都具有 Markdown 原生渲染库。"""

import argparse
from pathlib import Path
from zipfile import ZipFile


def verify(apk: Path, expected: set[str]) -> None:
    with ZipFile(apk) as archive:
        libraries = {
            name for name in archive.namelist()
            if name.startswith("lib/") and name.endswith(".so")
        }
    actual = {name.split("/")[1] for name in libraries}
    if actual != expected:
        raise ValueError(f"APK 架构不匹配：期望 {sorted(expected)}，实际 {sorted(actual)}")
    for abi in sorted(actual):
        if f"lib/{abi}/libmutsumi_md2svg.so" not in libraries:
            raise ValueError(f"{abi} 缺少 md2svg 原生渲染库")
    print(f"原生库验收通过：{', '.join(sorted(actual))}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--abis", default="arm64-v8a,armeabi-v7a,x86_64")
    args = parser.parse_args()
    verify(args.apk, set(args.abis.split(",")))
