"""许可证正文按 SHA-256 复用；每个依赖和文件名仍保留原文引用。"""
import hashlib
import re
import sys
from collections import OrderedDict
from pathlib import Path

MARKER = "## 共用许可证原文"

def compact(source: str) -> str:
    if MARKER in source:
        return source
    sections = re.split(r"(?=^## [^\n]+\n来源：)", source, flags=re.M)
    texts = OrderedDict()
    output = []
    for section in sections:
        parts = re.split(r"^### ([^\n]+)\n\n", section, flags=re.M)
        output.append(parts[0].rstrip())
        for name, body in zip(parts[1::2], parts[2::2]):
            body = body.strip()
            digest = hashlib.sha256(body.encode("utf-8")).hexdigest()
            texts[digest] = body
            output.append(f"### {name}\n\n原文复用：{digest}")
    output.append(MARKER)
    output.extend(f"### 原文 {digest}\n\n{body}" for digest, body in texts.items())
    return "\n\n".join(output) + "\n"

if __name__ == "__main__":
    path = Path(sys.argv[1])
    source = path.read_text(encoding="utf-8")
    result = compact(source)
    path.write_text(result, encoding="utf-8")
    print(f"许可证原文去重：{len(source.encode('utf-8')):,} → {len(result.encode('utf-8')):,} 字节")
