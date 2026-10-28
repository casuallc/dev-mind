"""Generate a review index without importing or executing historical scripts."""
import ast
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).with_name("python-inventory.md")


def render():
    legacy = json.loads(Path(__file__).with_name("legacy.json").read_text(encoding="utf-8"))
    files = sorted((ROOT / "tests").glob("*.py"))
    lines = ["# Python 测试迁移清单", "",
             f"共 {len(files)} 个顶层 Python 脚本。由 python_inventory.py 生成；只静态读取，不执行脚本。",
             "信号仅帮助审核，不代表已验证的前置或安全结论。legacy 不能直接批量导入可执行套件。", "",
             "| 脚本 | 状态 | 静态信号 | 替代/审核说明 |", "|---|---|---|---|"]
    for file in files:
        text = file.read_text(encoding="utf-8-sig")
        tree = ast.parse(text)
        constants = {n.value for n in ast.walk(tree) if isinstance(n, ast.Constant) and isinstance(n.value, str)}
        signals = []
        if constants & {"POST", "PUT", "PATCH", "DELETE"}:
            signals.append("含写请求（可能仅登录）")
        if "Popen" in text:
            signals.append("创建进程")
        if "runner.jar" in text:
            signals.append("runner")
        if re.search(r"https?://(?:localhost|127\.0\.0\.1):\d+", text):
            signals.append("含本机默认地址")
        info = legacy[file.name]
        reason = info["reason"].replace("|", "\\|")
        lines.append(f'| [{file.name}](../{file.name}) | {info["status"]} | {"、".join(signals) or "需人工审核"} | {reason} |')
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    expected = render()
    if "--write" in sys.argv:
        OUT.write_bytes(expected.encode("utf-8"))
    elif not OUT.exists() or OUT.read_text(encoding="utf-8") != expected:
        raise SystemExit("Python inventory stale: python tests/e2e/python_inventory.py --write")
    print("Python inventory OK")
