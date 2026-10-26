package com.devmind.worklog.service;

import com.devmind.session.dto.ScenarioRequest;
import com.devmind.session.repo.SessionScenarioRepository;
import com.devmind.session.service.ScenarioService;
import com.devmind.skill.SkillService;
import com.devmind.skill.dto.SkillRequest;
import com.devmind.skill.model.SkillEntity;
import com.devmind.skill.repo.SkillRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CAP-41 FR-04 启动期种子：GLOBAL worklog skill + worklog-daily / worklog-weekly 场景。
 *
 * <p>「不存在才建」——种子只在首启写入，之后全部走现有 skill/场景管理页维护
 * （管控台可见可改，改动对新会话即时生效，无需重启）。种子失败只告警不阻断启动。</p>
 *
 * <p>场景 preset permissionMode=bypassPermissions：日报/周报多为定时无人值守触发，
 * 工具授权弹窗会让会话永远挂起；工作区是个人日志目录（非代码库），风险可控。</p>
 */
@Component
public class WorklogAssetSeeder {

    private static final Logger log = LoggerFactory.getLogger(WorklogAssetSeeder.class);

    /**
     * 内置生成场景的自动结束预设（CAP-33 FR-08）：回合完成后空闲 60s 自动结束会话，
     * runner 随 exit 自动回传成稿、服务端写仓——定时无人值守链路不再需要
     * 手动「推送工作日志」+「结束会话」。
     */
    static final int AUTO_FINISH_SEC = 60;

    /** skill 正文（SKILL.md frontmatter 之后的部分；frontmatter 由结构化字段自动拼装） */
    static final String SKILL_CONTENT = """
            # 工作日志草稿区约定

            你在一个一次性的工作日志草稿区中工作（普通目录，非 git 仓库；会话结束后可能被清理）。
            事实源是平台服务端的工作日志仓——成稿不落在草稿区持久保存，写完必须复制到回传目录上交平台。

            ## 写作要求
            - 中文、事实导向的工作口吻，每条一句话说清做了什么与结果
            - 内容可能进入团队周报与绩效材料：不要写入密码、密钥、token、内部地址等敏感信息
            - 素材以 prompt 中携带的快照为准（周报任务的本周日报全文已在任务说明里），不得凭空概括；
              草稿区没有历史文件可回读，不要尝试找旧稿

            ## 成稿回传（平台契约，必须执行）
            - 日报成稿写到 `.devmind/output/daily-yyyy-MM-dd.md`
            - 周报成稿写到 `.devmind/output/weekly-yyyy-Www.md`
            - 建议计划成稿写到 `.devmind/output/plan-suggest-yyyy-Www.md`（逐行 `- [类型] 标题（预估 Xh）`）
            - 季度复盘成稿写到 `.devmind/output/quarterly-review-<period>.md`（period 如 2026-Q4）
            - 平台在会话结束时自动采集该目录写入服务端工作日志仓（git 历史留痕）；缺文件则本次生成判失败

            ## 追加工作条目（如需）
            工作条目也在服务端仓里，每天一个文件 `<你的目录>/entries/yyyy-MM-dd.md`。追加格式：
            ```
            <!-- devmind:entry id=<32位小写hex> type=DEV hours=1.5 source=AGENT repo=- sha=- req=- jira=- created=<ISO-8601 UTC 秒级> -->
            ## 条目标题
            正文（可空）。
            ```
            - id 用新生成的 32 位小写 hex（uuid 去横线）；type ∈ DEV/SUPPORT/MEETING/RESEARCH/OTHER；
              hours 按 0.25 步进（15 分钟=0.25）；空值属性写 `-`
            - 只在文件尾部追加新条目块，不要改动已有条目注释行与 preamble（首个注释之前的内容）
            - 条目块之间空一行；正文里可以随便用 markdown（含 `##` 标题与代码块）
            """;

    /** 场景背景（装配进 CLAUDE.md「场景背景」节） */
    static final String EXTRA_CONTEXT = """
            本工作区是「工作日志空间」的会话草稿区：runner 侧的临时工作目录（非 git 仓库，无持久性承诺）。
            事实源是平台服务端的工作日志 git 仓；你的唯一交付方式是把成稿写进 .devmind/output/ 回传目录，
            由平台在会话结束时写仓留痕。草稿区内不做 git 操作。
            """;

    static final String DAILY_SKELETON = """
            {{task}}

            ## 执行要求（遵循 worklog skill 约定）
            1. 素材以上方任务说明为准（当日条目与 git 提交已附上）；
            2. 成稿写入 .devmind/output/daily-<日期>.md（日期见上方任务说明；平台回传契约，缺文件判失败）；
            3. 最后一句话确认已完成。
            """;

    static final String WEEKLY_SKELETON = """
            {{task}}

            ## 执行要求（遵循 worklog skill 约定）
            1. 先通读上方任务说明里附带的本周日报全文再动笔，不得凭空概括；
            2. 成稿写入 .devmind/output/weekly-<yyyy-Www>.md（ISO 周，见上方任务说明；平台回传契约，缺文件判失败）；
            3. 最后一句话确认已完成。
            """;

    /**
     * CAP-88 M3「AI 建议计划」场景骨架。产物行格式是服务端解析契约
     * （{@code PlanSuggestParser}）：`- [类型] 标题（预估 Xh）`，改格式必须同步解析器。
     */
    static final String PLAN_SUGGEST_SKELETON = """
            {{task}}

            ## 执行要求（遵循 worklog skill 约定）
            1. 素材以上方任务说明为准（上周未完成/滚转项与在办需求已附上），建议项必须能从素材追溯，不写空话；
            2. 给出 3~6 条建议，每条一行，严格按此格式（平台按行解析，格式错整条丢弃）：
               `- [DEV] 标题（预估 2h）`
               - 类型 ∈ DEV/SUPPORT/MEETING/RESEARCH/OTHER；预估工时可省略（不写括号段）；
               - 除建议行外不写别的内容（不要标题、不要解释、不要代码块）；
            3. 成稿写入 .devmind/output/plan-suggest-<yyyy-Www>.md（ISO 周，见上方任务说明；平台回传契约，缺文件判失败）；
            4. 最后一句话确认已完成。
            """;

    /**
     * CAP-91 M2「季度复盘」场景骨架。产物文件名契约 quarterly-review-<period>.md 与
     * {@code WorklogOutputMirror.QUARTERLY_REVIEW_FILE} 解析共用，改动必须两处同步。
     */
    static final String QUARTERLY_REVIEW_SKELETON = """
            {{task}}

            ## 执行要求（遵循 worklog skill 约定）
            1. 素材以上方任务说明为准（当季 OKR 对照与计划外工作聚合已附上），数字与状态以素材为准，不得编造；
            2. 成稿写入 .devmind/output/quarterly-review-<period>.md（period 如 2026-Q4，见上方任务说明；平台回传契约，缺文件判失败）；
            3. 最后一句话确认已完成。
            """;

    private final SkillRepository skillRepo;
    private final SkillService skillService;
    private final SessionScenarioRepository scenarioRepo;
    private final ScenarioService scenarioService;

    public WorklogAssetSeeder(SkillRepository skillRepo, SkillService skillService,
                              SessionScenarioRepository scenarioRepo, ScenarioService scenarioService) {
        this.skillRepo = skillRepo;
        this.skillService = skillService;
        this.scenarioRepo = scenarioRepo;
        this.scenarioService = scenarioService;
    }

    @PostConstruct
    public void seed() {
        try {
            String skillId = ensureSkill();
            ensureScenario(WorklogScenarios.DAILY, "工作日志·日报",
                    "生成当日工作日志（草稿区成稿回传服务端仓）", DAILY_SKELETON, skillId, 90);
            ensureScenario(WorklogScenarios.WEEKLY, "工作日志·周报",
                    "生成一周工作周报（素材随 prompt 携带，成稿回传服务端仓）", WEEKLY_SKELETON, skillId, 91);
            ensureScenario(WorklogScenarios.PLAN_SUGGEST, "工作日志·建议计划",
                    "AI 建议下周计划（素材随 prompt 携带，产物解析为建议计划项）",
                    PLAN_SUGGEST_SKELETON, skillId, 92);
            ensureScenario(WorklogScenarios.QUARTERLY_REVIEW, "工作日志·季度复盘",
                    "生成季度复盘（OKR 对照 + 计划外聚合素材随 prompt 携带，成稿回传落库）",
                    QUARTERLY_REVIEW_SKELETON, skillId, 93);
        } catch (Exception e) {
            log.warn("worklog skill/场景种子失败（不阻断启动）: {}", e.getMessage());
        }
    }

    /** GLOBAL skill worklog：存在返回其 id，不存在创建。 */
    private String ensureSkill() {
        var existing = skillRepo.findByScopeAndProjectIdAndName(
                SkillEntity.SCOPE_GLOBAL, SkillEntity.GLOBAL_PROJECT_ID, WorklogScenarios.SKILL_NAME);
        if (existing.isPresent()) {
            return existing.get().getId();
        }
        var view = skillService.create(new SkillRequest(SkillEntity.SCOPE_GLOBAL, null,
                WorklogScenarios.SKILL_NAME,
                "工作日志草稿区工作约定：成稿回传契约与写作口吻（日报/周报生成场景默认绑定）",
                SKILL_CONTENT, null, List.of("worklog"), SkillEntity.STATUS_ACTIVE));
        log.info("worklog skill 种子已创建: id={}", view.id());
        return view.id();
    }

    /** 场景：code 存在则跳过正文（用户可能已改，种子不覆盖）；autoFinishSec 为 null 才回填默认值。 */
    private void ensureScenario(String code, String name, String description,
                                String skeleton, String skillId, int sortOrder) {
        var existing = scenarioRepo.findByCode(code).orElse(null);
        if (existing != null) {
            // 存量行回填自动结束预设（新列首启为 null；用户显式设过的值——含 0=永不——不覆盖）
            if (existing.getAutoFinishSec() == null) {
                existing.setAutoFinishSec(AUTO_FINISH_SEC);
                scenarioRepo.save(existing);
                log.info("worklog 场景回填自动结束预设: code={} autoFinishSec={}", code, AUTO_FINISH_SEC);
            }
            return;
        }
        scenarioService.save(null, new ScenarioRequest(code, name, description, skeleton,
                List.of(skillId), null, null, EXTRA_CONTEXT,
                null, "bypassPermissions", null,
                ScenarioService.SCOPE_GLOBAL, null, Boolean.TRUE, sortOrder, AUTO_FINISH_SEC));
        log.info("worklog 场景种子已创建: code={}", code);
    }
}
