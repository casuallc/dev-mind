package com.devmind.project.service;

import com.devmind.common.agent.OneShotAgentRunner;
import com.devmind.common.authz.AuthorizationService;
import com.devmind.common.authz.ResourceTarget;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.dto.AiExpandRequest;
import com.devmind.project.dto.AiExpandResponse;
import com.devmind.project.model.ProjectEntity;
import com.devmind.project.model.RequirementEntity;
import com.devmind.project.repo.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

/**
 * CAP-105 需求 AI 扩展：用户关键点 → one-shot 同步会话（CAP-28 SPI）→ 结构化草稿
 * （标题/类型/Markdown 描述），只回填表单不落库，人工确认后才走正常 create/update。
 *
 * <p>产物契约 = 单一 JSON 对象；解析失败 fail-visible（不做正文片段凑合降级）。
 * 执行体未装配（devmind-session 不在依赖图）时 409，与节点路由失败同一错误语义。</p>
 */
@Service
public class RequirementAiExpandService {

    private static final Logger log = LoggerFactory.getLogger(RequirementAiExpandService.class);

    private static final Set<String> TYPES = Set.of(
            RequirementEntity.TYPE_FEATURE, RequirementEntity.TYPE_BUG,
            RequirementEntity.TYPE_IMPROVEMENT, RequirementEntity.TYPE_TASK);

    /** title 列长 256，AI 给出超长标题时截断而不是报错（描述足够长才是核心价值） */
    private static final int TITLE_MAX = 256;

    private final ProjectRepository projectRepo;
    private final ObjectProvider<OneShotAgentRunner> oneShot;
    private final int timeoutSeconds;
    /** CAP-108 M1：核心授权（expand 硬校验，GENERATE 消耗模型预算） */
    private final AuthorizationService authz;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    public RequirementAiExpandService(ProjectRepository projectRepo,
                                      ObjectProvider<OneShotAgentRunner> oneShot,
                                      @Value("${devmind.project.ai-expand.timeout-seconds:120}") int timeoutSeconds,
                                      AuthorizationService authz) {
        this.projectRepo = projectRepo;
        this.oneShot = oneShot;
        this.timeoutSeconds = timeoutSeconds;
        this.authz = authz;
    }

    public AiExpandResponse expand(String projectId, AiExpandRequest req) {
        authz.require("requirement.draft.generate", ResourceTarget.project(projectId));
        ProjectEntity project = projectRepo.findById(projectId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "项目不存在: " + projectId));
        OneShotAgentRunner runner = oneShot.getIfAvailable();
        if (runner == null) {
            throw new DevMindException(ErrorCode.CONFLICT, "AI 执行体不可用（会话模块未装配）");
        }
        OneShotAgentRunner.Result result = runner.run(buildPrompt(project.getName(), req), timeoutSeconds);
        AiExpandResponse parsed = parse(result.sessionId(), result.summary());
        log.info("需求 AI 扩展完成: project={} session={} title={}", projectId, result.sessionId(), parsed.title());
        return parsed;
    }

    /** prompt 全文内联素材（one-shot 无文件语境）；模型被要求只输出一个 JSON 对象。 */
    String buildPrompt(String projectName, AiExpandRequest req) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("你是研发需求分析助手。根据用户给出的需求关键点，扩展成一份结构化的需求草稿。\n");
        sb.append("硬性要求：\n");
        sb.append("- 不读写任何文件，不执行任何命令，不查任何资料，只输出结果正文。\n");
        sb.append("- 只输出一个 JSON 对象，不要输出任何其他文字、解释或代码围栏。\n");
        sb.append("- JSON 格式：{\"title\":\"...\",\"type\":\"...\",\"description\":\"...\"}\n");
        sb.append("- title：一句话需求标题，不超过 60 字。\n");
        sb.append("- type：四选一 FEATURE / BUG / IMPROVEMENT / TASK，拿不准用 FEATURE。\n");
        sb.append("- description：Markdown 正文，包含「背景与目标」「需求描述」「验收标准」三个小节；");
        sb.append("基于关键点合理扩展，不得编造文中没有的具体数据、指标或人名。\n");
        if (projectName != null && !projectName.isBlank()) {
            sb.append("\n所属项目：").append(projectName.trim()).append('\n');
        }
        if (req.title() != null && !req.title().isBlank()) {
            sb.append("\n用户当前标题（可在此基础上改进）：\n").append(req.title().trim()).append('\n');
        }
        if (req.description() != null && !req.description().isBlank()) {
            sb.append("\n用户当前描述（可在此基础上扩写）：\n").append(req.description().trim()).append('\n');
        }
        sb.append("\n用户关键点：\n").append(req.points().trim()).append('\n');
        return sb.toString();
    }

    /** 从 one-shot summary 提取契约 JSON：容忍首尾散文与代码围栏，取首个平衡大括号对象。 */
    AiExpandResponse parse(String sessionId, String summary) {
        String json = extractJsonObject(summary);
        if (json == null) {
            throw new DevMindException(ErrorCode.INTERNAL,
                    "AI 扩展结果不是 JSON（session=" + sessionId + "）: " + abbreviate(summary));
        }
        JsonNode node;
        try {
            node = mapper.readTree(json);
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.INTERNAL,
                    "AI 扩展结果 JSON 解析失败（session=" + sessionId + "）: " + e.getMessage(), e);
        }
        String title = text(node, "title");
        String description = text(node, "description");
        if (title == null || title.isBlank() || description == null || description.isBlank()) {
            throw new DevMindException(ErrorCode.INTERNAL,
                    "AI 扩展结果缺少 title/description（session=" + sessionId + "）: " + abbreviate(summary));
        }
        if (title.length() > TITLE_MAX) {
            title = title.substring(0, TITLE_MAX);
        }
        String type = text(node, "type");
        type = type != null && TYPES.contains(type.trim().toUpperCase()) ? type.trim().toUpperCase() : null;
        return new AiExpandResponse(title.trim(), type, description.trim(), sessionId);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isString() ? v.asText() : null;
    }

    /** 首个平衡大括号子串（字符串与转义感知）；找不到返回 null。public 供单测直接钉契约。 */
    public static String extractJsonObject(String summary) {
        if (summary == null) {
            return null;
        }
        int start = summary.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < summary.length(); i++) {
            char c = summary.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return summary.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static String abbreviate(String summary) {
        if (summary == null) {
            return "(empty)";
        }
        String flat = summary.replace('\n', ' ').trim();
        return flat.length() > 200 ? flat.substring(0, 200) + "..." : flat;
    }
}
