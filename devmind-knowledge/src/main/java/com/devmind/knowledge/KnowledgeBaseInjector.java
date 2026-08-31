package com.devmind.knowledge;

import com.devmind.knowledge.config.KnowledgeProperties;
import com.devmind.knowledge.dto.EntryView;
import com.devmind.project.model.Project;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * CAP-04 注入器（替换 LocalDirInjector）：从知识库服务取「全局按标签命中 + 项目特有」条目，
 * 组装 CLAUDE.md 写入 worktree（项目原有内容追加在后，不覆盖），并落 .claude/settings.local.json。
 * 注入成功后对用到的条目 hitCount+1（FR-07 清理依据）。
 */
@Component
public class KnowledgeBaseInjector implements KnowledgeInjector {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseInjector.class);

    private final KnowledgeProperties props;
    private final KnowledgeBaseService service;

    public KnowledgeBaseInjector(KnowledgeProperties props, KnowledgeBaseService service) {
        this.props = props;
        this.service = service;
    }

    @Override
    public String apply(String worktreePath, Project project, String taskSpec) {
        if (!props.isEnabled()) {
            return "";
        }
        Path wt = Path.of(worktreePath).toAbsolutePath().normalize();

        List<EntryView> used = service.selectEntries(project);
        if (used.isEmpty()) {
            log.info("知识注入：无命中条目，跳过 worktree={}", wt);
            return "";
        }

        Path orig = wt.resolve("CLAUDE.md");
        String origContent = null;
        if (Files.exists(orig)) {
            try {
                origContent = Files.readString(orig, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("读取原 CLAUDE.md 失败: {}", orig, e);
            }
        }

        String content = ClaudeMd.assemble(used, taskSpec, origContent);
        try {
            Files.writeString(wt.resolve("CLAUDE.md"), content, StandardCharsets.UTF_8);
            writeSettingsLocal(wt);
            service.bumpHits(used);
            log.info("知识注入完成: worktree={} 条目={} 注入字节={}",
                    wt, used.size(), content.length());
        } catch (IOException e) {
            log.warn("知识注入写文件失败: {}", wt, e);
        }
        return content;
    }

    private void writeSettingsLocal(Path wt) throws IOException {
        Path dir = wt.resolve(".claude");
        Files.createDirectories(dir);
        String json = "{\n" +
                "  \"permissions\": {\n" +
                "    \"allow\": [\"Bash(npm:*)\", \"Bash(mvn:*)\", \"Bash(git:*)\", \"Edit\", \"Write\", \"Read\"]\n" +
                "  }\n" +
                "}\n";
        Files.writeString(dir.resolve("settings.local.json"), json, StandardCharsets.UTF_8);
    }
}
