#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""CAP-87 M4 更新日志打包自动生成器（构建即版本锚点）。

用法（一般由 scripts/build-dist.sh 调用，也可手工）：
    scripts/changelog-gen.py            # 生成新条目并改写仓库根 CHANGELOG.md（不提交）
    scripts/changelog-gen.py --dry-run  # 只打印将生成的条目，不写文件
    scripts/changelog-gen.py --commit   # 若 CHANGELOG.md 有未提交改动则自动提交（不 push）

口径（详见 docs/capabilities/CAP-87-changelog.md 决策 5/6、FR-12/FR-13）：
- 锚点：最新已发布段落的 `- 提交：<sha>` 元信息行；缺失即报错（一次性 bootstrap，不猜日期）。
- 内容：git log --no-merges <anchor>..HEAD，按提交规约 <type>(<scope>): <描述> 归类：
  feat→新增、fix→修复、refactor/perf→优化；docs/style/test/chore/build/ci 不进日志；
  `!` 后缀或含「破坏性/BREAKING」一律归入破坏性变更。
- 覆盖层：`## [Unreleased]` 有正文时以其为新条目内容（人工润色优先，不再附自动明细），
  生成后清空该段；留空则完全由提交记录生成。
- 版本号：<pom 去 -SNAPSHOT>-<yyyymmdd>+<shortsha>，保证同日多次打包唯一。
- 幂等：锚点..HEAD 无新提交、或全部提交均不面向运维且无润色草稿时，跳过不写文件。
"""
from __future__ import annotations

import argparse
import datetime
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
CHANGELOG = REPO_ROOT / "CHANGELOG.md"
ROOT_POM = REPO_ROOT / "pom.xml"

# ---- 提交归类口径 ----
TYPE_TO_SECTION = {"feat": "新增", "fix": "修复", "refactor": "优化", "perf": "优化"}
SKIP_TYPES = {"docs", "style", "test", "chore", "build", "ci"}
SECTION_ORDER = ["新增", "优化", "修复", "破坏性变更"]

SUBJECT_RE = re.compile(r"^(\w+)(?:\(([^)]*)\))?(!)?:\s*(.+)$")
BREAKING_RE = re.compile(r"破坏性|BREAKING", re.IGNORECASE)
REF_RE = re.compile(r"\b(CAP|REQ|WI)-(\d+)\b", re.IGNORECASE)

COMMIT_META_RE = re.compile(r"^\s{0,3}[-*+]\s*(?:提交|commit)\s*[:：]\s*([0-9a-fA-F]{7,40})\s*$",
                            re.MULTILINE)
PUBLISHED_HEADING_RE = re.compile(r"^##\s+\[?(\d[^\]\s]*)\]?", re.MULTILINE)
UNRELEASED_HEADING_RE = re.compile(r"^##\s+\[?\s*(unreleased|un-released|未发布|待发布)\s*\]?",
                                   re.IGNORECASE | re.MULTILINE)

UNRELEASED_SCAFFOLD = (
    "## [Unreleased]\n\n"
    "<!-- 可选：在此人工起草下一版公告，打包时优先转正并清空；留空则按提交记录自动生成。 -->\n"
)

REF_PREFIX_ORDER = {"CAP": 0, "REQ": 1, "WI": 2}


def git(*args: str) -> str:
    return subprocess.run(
        ["git", "-C", str(REPO_ROOT), *args],
        check=True, capture_output=True,
    ).stdout.decode("utf-8", errors="replace")


# ---------------- 纯函数（单测覆盖） ----------------

def parse_commit(full_sha: str, subject: str, body: str) -> dict | None:
    """解析一条提交为归类单元；不面向运维的类型返回 None。"""
    m = SUBJECT_RE.match(subject.strip())
    if not m:
        return None
    ctype, scope, bang, desc = m.group(1).lower(), m.group(2), m.group(3), m.group(4).strip()
    breaking = bool(bang) or bool(BREAKING_RE.search(subject)) or bool(BREAKING_RE.search(body))
    if ctype in SKIP_TYPES and not breaking:
        return None
    section = "破坏性变更" if breaking else TYPE_TO_SECTION.get(ctype)
    if section is None:
        return None
    refs = sorted({f"{p.upper()}-{n}" for p, n in REF_RE.findall(subject + "\n" + body)},
                  key=lambda r: (REF_PREFIX_ORDER.get(r.split("-")[0], 9),
                                 int(r.split("-")[1])))
    return {"section": section, "scope": scope, "desc": desc,
            "sha": full_sha[:7], "refs": refs}


def item_line(item: dict) -> str:
    prefix = f"**{item['scope']}**：" if item.get("scope") else ""
    return f"- {prefix}{item['desc']}（{item['sha']}）"


def build_auto_body(items: list[dict]) -> str:
    sections: dict[str, list[str]] = {}
    for it in items:
        sections.setdefault(it["section"], []).append(item_line(it))
    parts = []
    for name in SECTION_ORDER:
        lines = sections.get(name)
        if lines:
            parts.append("### " + name + "\n\n" + "\n".join(lines))
    return "\n\n".join(parts)


def split_sections(text: str) -> tuple[str, str, str]:
    """切分 CHANGELOG 为 (头部, Unreleased 正文, 已发布段落原文)。"""
    headings = list(re.finditer(r"^## .*$", text, re.MULTILINE))
    if not headings:
        return text.rstrip() + "\n\n", "", ""
    head = text[: headings[0].start()]
    blocks: list[tuple[str, str]] = []  # (heading_line, body)
    for i, h in enumerate(headings):
        end = headings[i + 1].start() if i + 1 < len(headings) else len(text)
        blocks.append((h.group(0), text[h.end():end]))
    unreleased = ""
    published: list[str] = []
    for heading_line, body in blocks:
        if UNRELEASED_HEADING_RE.match(heading_line):
            unreleased = body
        else:
            published.append(heading_line + body)
    return head, unreleased, "".join(published)


def strip_scaffold(unreleased_body: str) -> str:
    """去掉脚手架注释与空白后的人工润色正文；空串表示没有润色。"""
    text = re.sub(r"<!--.*?-->", "", unreleased_body, flags=re.DOTALL)
    return text.strip()


def find_anchor(published_text: str) -> str | None:
    """最新已发布段落（文件序第一段）的 `- 提交：<sha>` 锚点。"""
    m = PUBLISHED_HEADING_RE.search(published_text)
    if not m:
        return None
    first_block = published_text[m.start():]
    next_heading = re.search(r"^## ", first_block[1:], re.MULTILINE)
    block = first_block[: next_heading.start() + 1] if next_heading else first_block
    anchor = COMMIT_META_RE.search(block)
    return anchor.group(1).lower() if anchor else None


def pom_base_version(pom_text: str) -> str:
    m = re.search(r"<artifactId>devmind</artifactId>\s*<version>([^<]+)</version>", pom_text)
    if not m:
        raise SystemExit("ERROR: 无法从根 pom.xml 解析 devmind 版本")
    return re.sub(r"-SNAPSHOT$", "", m.group(1).strip())


def build_entry(version: str, sha: str, refs: list[str], body: str) -> str:
    today = datetime.date.today().isoformat()
    lines = [f"## [{version}] - {today}", "", f"- 提交：{sha}"]
    if refs:
        lines.append("- 关联：" + ", ".join(refs))
    return "\n".join(lines) + "\n\n" + body.strip() + "\n\n"


def assemble(head: str, entry: str, published: str) -> str:
    return (head.rstrip() + "\n\n" + UNRELEASED_SCAFFOLD + "\n" + entry
            + published.lstrip("\n"))


# ---------------- 流程 ----------------

def collect_items(anchor: str) -> list[dict]:
    raw = git("log", "--no-merges", "--encoding=UTF-8",
              "--format=%H%x1f%s%x1f%b%x1e", f"{anchor}..HEAD")
    items: list[dict] = []
    skipped = 0
    for record in raw.split("\x1e"):
        record = record.strip("\n")
        if not record.strip():
            continue
        parts = record.split("\x1f")
        if len(parts) < 3:
            continue
        item = parse_commit(parts[0], parts[1], parts[2])
        if item:
            items.append(item)
        else:
            skipped += 1
    if skipped:
        print(f"[changelog-gen] {skipped} 条提交不面向运维（docs/style/test/chore 等），已跳过")
    return items


def generate(dry_run: bool) -> int:
    text = CHANGELOG.read_text(encoding="utf-8")
    head, unreleased_raw, published = split_sections(text)

    anchor = find_anchor(published)
    if not anchor:
        print("ERROR: 最新已发布段落缺少 `- 提交：<sha>` 锚点；请为最新段落一次性补登后重试",
              file=sys.stderr)
        return 2
    head_sha = git("rev-parse", "--short=7", "HEAD").strip()
    if anchor == head_sha.lower() or anchor == git("rev-parse", "HEAD").strip().lower():
        print(f"[changelog-gen] 锚点即 HEAD（{head_sha}），无新提交，跳过")
        return 0

    try:
        items = collect_items(anchor)
    except subprocess.CalledProcessError as e:
        err = e.stderr.decode("utf-8", errors="replace").strip() if e.stderr else str(e)
        print(f"ERROR: git log {anchor}..HEAD 失败（锚点不在历史中？）：{err}", file=sys.stderr)
        return 2
    curated = strip_scaffold(unreleased_raw)
    if not items and not curated:
        print(f"[changelog-gen] {anchor}..HEAD 无面向运维的变更且无润色草稿，跳过")
        return 0

    refs = sorted({r for it in items for r in it["refs"]} |
                  {f"{p.upper()}-{n}" for p, n in REF_RE.findall(curated)},
                  key=lambda r: (REF_PREFIX_ORDER.get(r.split("-")[0], 9),
                                 int(r.split("-")[1])))
    body = curated if curated else build_auto_body(items)
    base = pom_base_version(ROOT_POM.read_text(encoding="utf-8"))
    version = f"{base}-{datetime.date.today():%Y%m%d}+{head_sha}"
    entry = build_entry(version, head_sha, refs, body)

    if dry_run:
        print(entry)
        print(f"[changelog-gen] dry-run：{len(items)} 条提交归类"
              f"{'（Unreleased 润色优先）' if curated else ''}，未写文件")
        return 0
    CHANGELOG.write_text(assemble(head, entry, published), encoding="utf-8", newline="\n")
    print(f"[changelog-gen] 新条目 [{version}]：{len(items)} 条提交"
          f"{' + Unreleased 润色' if curated else ''}，CHANGELOG.md 已更新（未提交）")
    return 0


def commit() -> int:
    dirty = subprocess.run(
        ["git", "-C", str(REPO_ROOT), "status", "--porcelain", "CHANGELOG.md"],
        check=True, capture_output=True).stdout.decode("utf-8", errors="replace").strip()
    if not dirty:
        print("[changelog-gen] CHANGELOG.md 无改动，无需提交")
        return 0
    text = CHANGELOG.read_text(encoding="utf-8")
    m = PUBLISHED_HEADING_RE.search(text)
    version = m.group(1) if m else "unknown"
    git("add", "CHANGELOG.md")
    git("commit", "-m", f"docs(changelog): 发版 {version} 自动转正")
    print(f"[changelog-gen] 已提交 docs(changelog): 发版 {version} 自动转正（未 push）")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="CAP-87 更新日志打包自动生成器")
    ap.add_argument("--dry-run", action="store_true", help="只打印将生成的条目，不写文件")
    ap.add_argument("--commit", action="store_true", help="提交 CHANGELOG.md 改动（不 push）")
    args = ap.parse_args()
    if args.commit:
        return commit()
    return generate(dry_run=args.dry_run)


if __name__ == "__main__":
    sys.exit(main())
