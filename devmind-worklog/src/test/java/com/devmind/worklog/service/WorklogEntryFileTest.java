package com.devmind.worklog.service;

import com.devmind.worklog.service.WorklogEntryFile.DayFile;
import com.devmind.worklog.service.WorklogEntryFile.ParsedEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** CAP-74 M3：条目 day 文件解析/序列化往返钉死。 */
class WorklogEntryFileTest {

    @Test
    void roundTripPreservesPreambleEntriesAndSeparators() {
        String content = """
                # 2026-10-12 工作条目

                自由前言文本，claude 手写。

                <!-- devmind:entry id=9f3a2c1e7b4d4f0a8c5e2d1b6a9f0e3d type=DEV hours=1.5 source=MANUAL repo=- sha=- req=REQ-101 jira=- created=2026-10-12T02:30:00Z -->
                ## 实现仓级锁
                接入 ReentrantLock，30s 超时返回 409。

                <!-- devmind:entry id=8e2b1a0c9d8f4e6b5a4c3d2e1f0a9b8c type=DEV hours=2 source=GIT repo=42 sha=a1b2c3d4 req=- jira=- created=2026-10-12T03:00:00Z -->
                ## feat(worklog): 服务端直推

                """;
        DayFile f = WorklogEntryFile.parse(content);
        assertThat(f.preamble()).isEqualTo("# 2026-10-12 工作条目\n\n自由前言文本，claude 手写。\n\n");
        assertThat(f.entries()).hasSize(2);
        ParsedEntry e1 = f.entries().get(0);
        assertThat(e1.id()).isEqualTo("9f3a2c1e7b4d4f0a8c5e2d1b6a9f0e3d");
        assertThat(e1.entryType()).isEqualTo("DEV");
        assertThat(e1.minutes()).isEqualTo(90);
        assertThat(e1.source()).isEqualTo("MANUAL");
        assertThat(e1.requirementId()).isEqualTo("REQ-101");
        assertThat(e1.jiraIssueKey()).isNull();
        assertThat(e1.createdAt()).isEqualTo(Instant.parse("2026-10-12T02:30:00Z"));
        assertThat(e1.title()).isEqualTo("实现仓级锁");
        assertThat(e1.body()).isEqualTo("接入 ReentrantLock，30s 超时返回 409。");
        ParsedEntry e2 = f.entries().get(1);
        assertThat(e2.repoId()).isEqualTo(42L);
        assertThat(e2.commitSha()).isEqualTo("a1b2c3d4");
        assertThat(e2.minutes()).isEqualTo(120);
        assertThat(e2.body()).isEmpty();
        // 字节级往返
        assertThat(WorklogEntryFile.render(f)).isEqualTo(content);
    }

    @Test
    void unknownAttrsSurviveRoundTripInOrder() {
        String content = """
                <!-- devmind:entry id=ab12 type=DEV hours=0.25 source=MANUAL repo=- sha=- req=- jira=- created=2026-10-12T02:30:00Z legacy=731 mood=good -->
                ## t
                b

                """;
        DayFile f = WorklogEntryFile.parse(content);
        ParsedEntry e = f.entries().get(0);
        assertThat(e.extraAttrs()).containsExactly(Map.entry("legacy", "731"), Map.entry("mood", "good"));
        assertThat(WorklogEntryFile.render(f)).isEqualTo(content);
    }

    @Test
    void bodyKeepsSubHeadingsCodeBlocksAndInnerBlankLines() {
        String content = """
                <!-- devmind:entry id=ab12 type=DEV hours=1 source=MANUAL repo=- sha=- req=- jira=- created=2026-10-12T02:30:00Z -->
                ## 标题
                第一行。

                ### 小节
                ```java
                int a = 1;
                ```

                收尾。

                """;
        DayFile f = WorklogEntryFile.parse(content);
        assertThat(f.entries().get(0).body()).isEqualTo(
                "第一行。\n\n### 小节\n```java\nint a = 1;\n```\n\n收尾。");
        assertThat(WorklogEntryFile.render(f)).isEqualTo(content);
    }

    @Test
    void missingIdAndHoursTolerated() {
        String content = """
                <!-- devmind:entry type=SUPPORT source=MANUAL req=- jira=- -->
                ## 无 id 无 hours
                正文
                """;
        DayFile f = WorklogEntryFile.parse(content);
        ParsedEntry e = f.entries().get(0);
        assertThat(e.id()).isNull();
        assertThat(e.minutes()).isZero();
        assertThat(e.createdAt()).isNull();
        assertThat(e.entryType()).isEqualTo("SUPPORT");
        // 重写时惰性补发 id
        String rendered = WorklogEntryFile.render(f);
        DayFile f2 = WorklogEntryFile.parse(rendered);
        assertThat(f2.entries().get(0).id()).matches("[0-9a-f]{32}");
    }

    @Test
    void emptyAndPreambleOnlyFilesParse() {
        assertThat(WorklogEntryFile.parse(null).entries()).isEmpty();
        assertThat(WorklogEntryFile.parse("").entries()).isEmpty();
        DayFile f = WorklogEntryFile.parse("# 2026-10-12 工作条目\n\n只有前言。\n");
        assertThat(f.entries()).isEmpty();
        assertThat(f.preamble()).isEqualTo("# 2026-10-12 工作条目\n\n只有前言。\n");
        assertThat(WorklogEntryFile.render(f)).isEqualTo("# 2026-10-12 工作条目\n\n只有前言。\n");
    }

    @Test
    void bareTextAfterEntryJoinsPreviousEntryBody() {
        String content = """
                <!-- devmind:entry id=ab12 type=DEV hours=1 source=MANUAL req=- jira=- created=2026-10-12T02:30:00Z -->
                ## 标题
                正文

                ## 人类追加的裸标题
                裸文本归上一条目。
                """;
        DayFile f = WorklogEntryFile.parse(content);
        assertThat(f.entries()).hasSize(1);
        assertThat(f.entries().get(0).body()).contains("## 人类追加的裸标题").contains("裸文本归上一条目。");
    }

    @Test
    void hoursFormatting() {
        assertThat(WorklogEntryFile.toHours(15)).isEqualTo("0.25");
        assertThat(WorklogEntryFile.toHours(30)).isEqualTo("0.5");
        assertThat(WorklogEntryFile.toHours(60)).isEqualTo("1");
        assertThat(WorklogEntryFile.toHours(90)).isEqualTo("1.5");
        assertThat(WorklogEntryFile.toHours(0)).isEqualTo("0");
        assertThat(WorklogEntryFile.toMinutes(1.5)).isEqualTo(90);
        assertThat(WorklogEntryFile.toMinutes(0.26)).isEqualTo(15); // 非步进值四舍五入到 15 分钟
    }

    @Test
    void crlfNormalizedOnParse() {
        DayFile f = WorklogEntryFile.parse(
                "# 前言\r\n\r\n<!-- devmind:entry id=ab12 type=DEV hours=1 source=MANUAL req=- jira=- created=- -->\r\n## t\r\nb\r\n");
        assertThat(f.preamble()).isEqualTo("# 前言\n\n");
        assertThat(f.entries().get(0).body()).isEqualTo("b");
        assertThat(WorklogEntryFile.render(f)).doesNotContain("\r");
    }

    @Test
    void renderBuildsFromScratchWithInitialPreamble() {
        DayFile f = new DayFile(WorklogEntryFile.initialPreamble("2026-10-12"), List.of());
        ParsedEntry e = new ParsedEntry(WorklogEntryFile.newId(), "DEV", 45, "MANUAL",
                null, null, null, null, Instant.parse("2026-10-12T02:30:00Z"), "标题", "正文", null);
        f.entries().add(e);
        DayFile back = WorklogEntryFile.parse(WorklogEntryFile.render(f));
        assertThat(back.preamble()).isEqualTo("# 2026-10-12 工作条目\n\n");
        assertThat(back.entries()).hasSize(1);
        assertThat(back.entries().get(0).minutes()).isEqualTo(45);
        assertThat(back.entries().get(0).title()).isEqualTo("标题");
        assertThat(back.entries().get(0).body()).isEqualTo("正文");
    }
}
