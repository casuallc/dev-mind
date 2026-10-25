package com.devmind.changelog.service;

import com.devmind.changelog.config.ChangelogProperties;
import com.devmind.changelog.dto.ChangelogPageView;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChangelogServiceTest {

    @TempDir
    Path tempDir;

    private static final String MD = """
            # Dev-Mind 更新日志

            ## [Unreleased]
            ### 新增
            - 草稿不可见

            ## [1.2.0] - 2026-10-25
            - 关联：CAP-87

            ### 新增
            - 更新日志页面

            ## [1.1.0] - 2026-09-01
            ### 修复
            - 修复分页

            ## [1.0.0] - 2026-08-01
            ### 优化
            - 优化检索
            """;

    @Test
    void listsOnlyPublishedEntriesWithPagination() throws IOException {
        ChangelogService service = service(file(MD));

        ChangelogPageView page = service.list(null, null, null, null, 0, 2);

        assertThat(page.available()).isTrue();
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.items()).extracting(i -> i.version())
                .containsExactly("1.2.0", "1.1.0");
        assertThat(service.list(null, null, null, null, 1, 2).items())
                .extracting(i -> i.version()).containsExactly("1.0.0");
    }

    @Test
    void filtersByVersionDateAndKeyword() throws IOException {
        ChangelogService service = service(file(MD));

        assertThat(service.list(null, "1.1", null, null, 0, 20).items())
                .extracting(i -> i.version()).containsExactly("1.1.0");
        assertThat(service.list(null, "v1.2.0", null, null, 0, 20).items())
                .extracting(i -> i.version()).containsExactly("1.2.0");

        assertThat(service.list(null, null, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1), 0, 20).items())
                .extracting(i -> i.version()).containsExactly("1.1.0");

        assertThat(service.list("CAP-87", null, null, null, 0, 20).items())
                .extracting(i -> i.version()).containsExactly("1.2.0");
        assertThat(service.list("分页", null, null, null, 0, 20).items())
                .extracting(i -> i.version()).containsExactly("1.1.0");
        assertThat(service.list("不存在", null, null, null, 0, 20).items()).isEmpty();
    }

    @Test
    void returnsDetailAnd404ForUnknownVersion() throws IOException {
        ChangelogService service = service(file(MD));

        var detail = service.get("v1.2.0");
        assertThat(detail.version()).isEqualTo("1.2.0");
        assertThat(detail.contentMd()).contains("更新日志页面").doesNotContain("关联：");

        assertThatThrownBy(() -> service.get("9.9.9"))
                .isInstanceOf(DevMindException.class)
                .extracting(e -> ((DevMindException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void reportsUnavailableWhenFileMissing() {
        ChangelogProperties props = new ChangelogProperties();
        props.setPath(tempDir.resolve("missing.md").toString());
        ChangelogService service = new ChangelogService(props, new ChangelogParser());

        ChangelogPageView page = service.list(null, null, null, null, 0, 20);

        assertThat(page.available()).isFalse();
        assertThat(page.items()).isEmpty();
        assertThat(page.sourcePath()).isEqualTo("missing.md");
    }

    @Test
    void reloadsWhenFileChangesWithoutRestart() throws IOException {
        Path path = file(MD);
        ChangelogService service = service(path);
        assertThat(service.list(null, null, null, null, 0, 20).total()).isEqualTo(3);

        Files.writeString(path, MD + """

                ## [1.3.0] - 2026-11-01
                ### 新增
                - 新版本
                """, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis() + 5_000));

        assertThat(service.list(null, null, null, null, 0, 20).total()).isEqualTo(4);
        assertThat(service.get("1.3.0").summary()).contains("新版本");
    }

    @Test
    void rejectsOversizedFile() throws IOException {
        Path path = tempDir.resolve("CHANGELOG.md");
        Files.writeString(path, "## [1.0.0] - 2026-01-01\n### 新增\n- " + "x".repeat(2048),
                StandardCharsets.UTF_8);
        ChangelogProperties props = new ChangelogProperties();
        props.setPath(path.toString());
        props.setMaxSizeKb(1);
        ChangelogService service = new ChangelogService(props, new ChangelogParser());

        assertThatThrownBy(() -> service.list(null, null, null, null, 0, 20))
                .isInstanceOf(DevMindException.class)
                .extracting(e -> ((DevMindException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    private Path file(String content) throws IOException {
        Path path = tempDir.resolve("CHANGELOG.md");
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private ChangelogService service(Path path) {
        ChangelogProperties props = new ChangelogProperties();
        props.setPath(path.toString());
        return new ChangelogService(props, new ChangelogParser());
    }
}
