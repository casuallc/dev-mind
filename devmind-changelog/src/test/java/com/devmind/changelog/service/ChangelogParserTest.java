package com.devmind.changelog.service;

import com.devmind.changelog.model.ChangelogEntry;
import com.devmind.changelog.model.ChangelogType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChangelogParserTest {

    private final ChangelogParser parser = new ChangelogParser();

    @Test
    void parsesReleasedVersionsAndHidesUnreleased() {
        String md = """
                # Dev-Mind 更新日志

                ## [Unreleased]
                ### 新增
                - 开发中的功能

                ## [1.2.0] - 2026-10-25
                - 关联：CAP-87, WI-12

                ### 新增
                - 控制台更新日志页面

                ### 修复
                - 修复分页问题

                ## v1.1.0 - 2026-09-01
                ### 优化
                - 优化加载速度

                ## [0.9.0]
                ### 破坏性变更
                - 移除旧接口
                """;

        List<ChangelogEntry> entries = parser.parse(md);

        assertThat(entries).extracting(ChangelogEntry::version)
                .containsExactly("1.2.0", "1.1.0", "0.9.0");
        ChangelogEntry first = entries.get(0);
        assertThat(first.releasedAt()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(first.types()).containsExactly(ChangelogType.ADDED, ChangelogType.FIXED);
        assertThat(first.refs()).containsExactly("CAP-87", "WI-12");
        assertThat(first.contentMd()).contains("### 新增", "控制台更新日志页面")
                .doesNotContain("关联：");
        assertThat(first.summary()).contains("控制台更新日志页面", "修复分页问题");
        assertThat(entries.get(2).releasedAt()).isNull();
        assertThat(entries.get(2).types()).containsExactly(ChangelogType.BREAKING);
    }

    @Test
    void ignoresSectionsWithoutVersion() {
        String md = """
                ## 更新日志
                - 没有版本号的段落

                ## [1.0.0] - 2026-01-01
                ### 新增
                - ok
                """;

        assertThat(parser.parse(md)).extracting(ChangelogEntry::version)
                .containsExactly("1.0.0");
    }

    @Test
    void supportsEnglishSectionHeadings() {
        String md = """
                ## [2.0.0] - 2026-02-02
                ### Added
                - new
                ### Changed
                - changed
                ### Fixed
                - fixed
                ### BREAKING CHANGE
                - break
                """;

        assertThat(parser.parse(md).get(0).types())
                .containsExactly(ChangelogType.ADDED, ChangelogType.IMPROVED,
                        ChangelogType.FIXED, ChangelogType.BREAKING);
    }

    @Test
    void summarySkipsMarkdownTableRows() {
        String md = """
                ## [3.0.0] - 2026-03-03
                ### 新增
                - 列表页支持筛选
                | 场景 | 结果 |
                |---|---|
                | 表格 | 通过 |
                """;

        assertThat(parser.parse(md).get(0).summary()).isEqualTo("列表页支持筛选");
    }

    @Test
    void sortsVersionsNumericallyNotLexicographically() {
        assertThat(ChangelogParser.compareVersions("1.10.0", "1.9.0")).isPositive();
        assertThat(ChangelogParser.compareVersions("1.0.0", "1.0.0-rc1")).isPositive();
        assertThat(ChangelogParser.compareVersions("2.0.0", "2.0.0")).isZero();
    }

    @Test
    void toleratesEmptyInput() {
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse("# 只有标题\n")).isEmpty();
    }
}
