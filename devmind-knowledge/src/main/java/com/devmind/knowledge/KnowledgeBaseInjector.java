package com.devmind.knowledge;

import com.devmind.knowledge.config.KnowledgeProperties;
import com.devmind.knowledge.dto.EntryView;
import com.devmind.project.model.Project;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CAP-04 装配器（原 KnowledgeBaseInjector 的文件写操作已随 CAP-34 移交 runner 侧
 * devmind-common {@code agent.exec.ContextMaterializer}）：从知识库服务取「全局按标签命中 +
 * 项目特有」条目，组装 CLAUDE.md 注入块与 .claude/settings.local.json 内容（权限白名单
 * 属服务端策略，随包下发）。装配成功后对用到的条目 hitCount+1（FR-07 清理依据）。
 */
@Component
public class KnowledgeBaseInjector implements KnowledgeInjector {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseInjector.class);

    /** 注入会话工作区的权限白名单（服务端策略，物化由 runner 完成）。 */
    private static final String SETTINGS_LOCAL_JSON = "{\n" +
            "  \"permissions\": {\n" +
            "    \"allow\": [\"Bash(npm:*)\", \"Bash(mvn:*)\", \"Bash(git:*)\", \"Edit\", \"Write\", \"Read\"]\n" +
            "  }\n" +
            "}\n";

    private final KnowledgeProperties props;
    private final KnowledgeBaseService service;

    public KnowledgeBaseInjector(KnowledgeProperties props, KnowledgeBaseService service) {
        this.props = props;
        this.service = service;
    }

    @Override
    public InjectionPackage build(Project project, String taskSpec) {
        if (!props.isEnabled()) {
            return null;
        }
        List<EntryView> used = service.selectEntries(project);
        if (used.isEmpty()) {
            log.info("知识注入：无命中条目，跳过 project={}", project != null ? project.id() : null);
            return null;
        }
        String claudeMd = ClaudeMd.assemble(used, taskSpec, null);
        service.bumpHits(used);
        log.info("知识注入装配完成: project={} 条目={} 注入字节={}",
                project != null ? project.id() : null, used.size(), claudeMd.length());
        return new InjectionPackage(claudeMd, SETTINGS_LOCAL_JSON, used.size());
    }
}
