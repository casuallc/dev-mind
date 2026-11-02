# -*- coding: utf-8 -*-
"""CAP-87 M4 生成器纯函数单测：python -m unittest discover tests/unit -v
归类/锚点/切片/组装的口径钉死在这里；git 交互（collect_items/generate/commit）由
`scripts/changelog-gen.py --dry-run` 手工验收覆盖。"""
import importlib.util
import unittest
from pathlib import Path

_spec = importlib.util.spec_from_file_location(
    "changelog_gen", Path(__file__).resolve().parents[2] / "scripts" / "changelog-gen.py")
gen = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gen)


class ParseCommitTest(unittest.TestCase):
    def test_conventional_types_map_to_sections(self):
        cases = {
            "feat(auth): 角色管理双页签": "新增",
            "fix(test): 表格列截断": "修复",
            "refactor(session): 收口调度": "优化",
            "perf(frontend): 渲染瘦身": "优化",
        }
        for subject, section in cases.items():
            with self.subTest(subject=subject):
                item = gen.parse_commit("abcdef123456", subject, "")
                self.assertIsNotNone(item)
                self.assertEqual(section, item["section"])
                self.assertEqual("abcdef1", item["sha"])

    def test_ops_invisible_types_skipped(self):
        for subject in ["docs(capabilities): 修订", "style: fmt", "test: 补单测",
                        "chore: bump", "build: 打包", "ci: 流水线"]:
            with self.subTest(subject=subject):
                self.assertIsNone(gen.parse_commit("abcdef123456", subject, ""))

    def test_breaking_overrides_section_and_skip_type(self):
        item = gen.parse_commit("abcdef123456", "feat(auth)!: 下线旧接口", "")
        self.assertEqual("破坏性变更", item["section"])
        # docs 类型本不进日志，但内容含破坏性必须保留
        item2 = gen.parse_commit("abcdef123456", "docs(capabilities): CAP-92 收口，CHANGELOG 记破坏性变更", "")
        self.assertIsNotNone(item2)
        self.assertEqual("破坏性变更", item2["section"])

    def test_non_conventional_subject_skipped(self):
        self.assertIsNone(gen.parse_commit("abcdef123456", "随手一改", ""))
        self.assertIsNone(gen.parse_commit("abcdef123456", "Merge branch 'x'", ""))

    def test_refs_extracted_deduped_sorted(self):
        item = gen.parse_commit("abcdef123456",
                                "feat(knowledge): 知识注入收敛 cap-92，关联 CAP-04",
                                "详见 REQ-12 与 WI-3，重复 CAP-92")
        self.assertEqual(["CAP-04", "CAP-92", "REQ-12", "WI-3"], item["refs"])

    def test_item_line_formats_scope(self):
        with_scope = gen.parse_commit("abcdef123456", "fix(test): 修截断", "")
        self.assertEqual("- **test**：修截断（abcdef1）", gen.item_line(with_scope))
        no_scope = gen.parse_commit("abcdef123456", "fix: 修截断", "")
        self.assertEqual("- 修截断（abcdef1）", gen.item_line(no_scope))


class SectionSplitTest(unittest.TestCase):
    SAMPLE = (
        "# Dev-Mind 更新日志\n\n> 说明\n\n"
        "## [Unreleased]\n\n### 新增\n- 润色草稿\n\n"
        "## [0.1.0-20261102+a1b2c3d] - 2026-11-02\n\n- 提交：a1b2c3d\n\n### 新增\n- 上一条目\n\n"
        "## [0.1.0] - 2026-10-25\n\n- 提交：f1852db\n- 关联：CAP-86\n\n### 修复\n- 旧条目\n"
    )

    def test_split_head_unreleased_published(self):
        head, unreleased, published = gen.split_sections(self.SAMPLE)
        self.assertIn("# Dev-Mind 更新日志", head)
        self.assertIn("润色草稿", unreleased)
        self.assertTrue(published.startswith("## [0.1.0-20261102+a1b2c3d]"))
        self.assertIn("## [0.1.0] - 2026-10-25", published)
        self.assertNotIn("Unreleased", published)

    def test_anchor_is_newest_published_commit(self):
        _, _, published = gen.split_sections(self.SAMPLE)
        self.assertEqual("a1b2c3d", gen.find_anchor(published))

    def test_anchor_missing_returns_none(self):
        self.assertIsNone(gen.find_anchor("## [0.1.0] - 2026-10-25\n\n### 新增\n- x\n"))
        self.assertIsNone(gen.find_anchor(""))

    def test_strip_scaffold_leaves_only_curated_text(self):
        self.assertEqual("", gen.strip_scaffold("\n<!-- 脚手架注释 -->\n\n"))
        self.assertEqual("### 新增\n- 润色", gen.strip_scaffold("<!-- 注 -->\n### 新增\n- 润色\n"))

    def test_assemble_resets_unreleased_and_keeps_order(self):
        head, unreleased, published = gen.split_sections(self.SAMPLE)
        curated = gen.strip_scaffold(unreleased)
        # 模拟 generate 流程：润色正文进新条目，Unreleased 段被重置为脚手架
        entry = gen.build_entry("0.1.0-20261103+e9f8g7h", "e9f8g7h", ["CAP-87"], curated)
        out = gen.assemble(head, entry, published)
        self.assertLess(out.index("## [Unreleased]"), out.index("## [0.1.0-20261103+e9f8g7h]"))
        self.assertLess(out.index("## [0.1.0-20261103+e9f8g7h]"),
                        out.index("## [0.1.0-20261102+a1b2c3d]"))
        self.assertIn("润色草稿", out)
        self.assertNotIn("### 新增\n- 上一条目\n\n### 新增", out)
        self.assertIn("- 提交：e9f8g7h", out)
        self.assertIn("- 关联：CAP-87", out)


class BuildBodyTest(unittest.TestCase):
    def test_sections_follow_fixed_order(self):
        items = [
            gen.parse_commit("a" * 40, "fix(x): 修", ""),
            gen.parse_commit("b" * 40, "feat(x): 增", ""),
            gen.parse_commit("c" * 40, "feat(x)!: 破", ""),
            gen.parse_commit("d" * 40, "refactor(x): 优", ""),
        ]
        body = gen.build_auto_body([i for i in items if i])
        order = [body.index("### 新增"), body.index("### 优化"),
                 body.index("### 修复"), body.index("### 破坏性变更")]
        self.assertEqual(order, sorted(order))

    def test_pom_base_version_strips_snapshot(self):
        pom = '<groupId>com.devmind</groupId>\n<artifactId>devmind</artifactId>\n<version>0.1.0-SNAPSHOT</version>'
        self.assertEqual("0.1.0", gen.pom_base_version(pom))


if __name__ == "__main__":
    unittest.main()
