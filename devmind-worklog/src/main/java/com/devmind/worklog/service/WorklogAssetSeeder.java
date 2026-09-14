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

    /** skill 正文（SKILL.md frontmatter 之后的部分；frontmatter 由结构化字段自动拼装） */
    static final String SKILL_CONTENT = """
            # 工作日志工作区约定

            你在一个持久的工作日志工作区中工作（本地 git 仓库，所有产出必须提交）。

            ## 目录契约
            - `daily/yyyy-MM-dd.md` —— 日报，按日期命名（如 daily/2026-09-14.md）
            - `weekly/yyyy-Www.md` —— 周报，按 ISO 周命名（如 weekly/2026-W37.md）
            - `entries/` —— 素材与零散记录（预留，可按需维护）
            - `.devmind/output/` —— 平台回传目录（见末节，生成任务必须写）

            ## 写作要求
            - 中文、事实导向的工作口吻，每条一句话说清做了什么与结果
            - 内容可能进入团队周报与绩效材料：不要写入密码、密钥、token、内部地址等敏感信息
            - 动笔前先回读 daily/ 与 weekly/ 下的近期文件，保持口径连续；
              写周报时必须真实翻阅本周（必要时含上周）的日报文件，不得凭空概括

            ## git 规范
            - 每次成稿后 `git add` 相关文件并 commit，message 形如 `docs: daily 2026-09-14`
            - 只提交、不 push（本地 git 即事实源）；不删除历史文件

            ## 成稿回传（平台契约，必须执行）
            - 日报成稿同时复制一份到 `.devmind/output/daily-yyyy-MM-dd.md`
            - 周报成稿同时复制一份到 `.devmind/output/weekly-yyyy-Www.md`
            - 平台在会话结束时自动采集该目录落库展示；缺文件则本次生成判失败
            """;

    /** 场景背景（装配进 CLAUDE.md「场景背景」节） */
    static final String EXTRA_CONTEXT = """
            本工作区是「工作日志空间」：runner 本机的持久 git 目录（{user.home}/worklog/<用户名>）。
            事实源是工作区文件（git 历史即修改记录），平台数据库只是用于展示/通知的镜像。
            所有产出按 worklog skill 的目录契约落盘并 commit。
            """;

    static final String DAILY_SKELETON = """
            {{task}}

            ## 执行要求（遵循 worklog skill 约定）
            1. 可先回读 daily/ 下近日日报，保持口吻与格式连续；
            2. 成稿写入 daily/<日期>.md（日期见上方任务说明），随后 git add + commit；
            3. 把同一份成稿复制到 .devmind/output/daily-<日期>.md（平台回传契约，缺文件判失败）；
            4. 最后一句话确认已完成。
            """;

    static final String WEEKLY_SKELETON = """
            {{task}}

            ## 执行要求（遵循 worklog skill 约定）
            1. 先真实翻阅 daily/ 下本周（必要时含上周）的日报文件再动笔；
            2. 成稿写入 weekly/<yyyy-Www>.md（ISO 周，见上方任务说明），随后 git add + commit；
            3. 把同一份成稿复制到 .devmind/output/weekly-<yyyy-Www>.md（平台回传契约，缺文件判失败）；
            4. 最后一句话确认已完成。
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
                    "生成当日工作日志（工作日志空间内落盘 + 回传）", DAILY_SKELETON, skillId, 90);
            ensureScenario(WorklogScenarios.WEEKLY, "工作日志·周报",
                    "生成一周工作周报（回读日报文件 + 落盘 + 回传）", WEEKLY_SKELETON, skillId, 91);
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
                "工作日志空间工作约定：目录契约、git 提交规范、成稿回传与写作口吻（日报/周报生成场景默认绑定）",
                SKILL_CONTENT, null, List.of("worklog"), SkillEntity.STATUS_ACTIVE));
        log.info("worklog skill 种子已创建: id={}", view.id());
        return view.id();
    }

    /** 场景：code 存在则跳过（用户可能已改，种子不覆盖）。 */
    private void ensureScenario(String code, String name, String description,
                                String skeleton, String skillId, int sortOrder) {
        if (scenarioRepo.existsByCode(code)) {
            return;
        }
        scenarioService.save(null, new ScenarioRequest(code, name, description, skeleton,
                List.of(skillId), null, null, EXTRA_CONTEXT,
                null, "bypassPermissions", null,
                ScenarioService.SCOPE_GLOBAL, null, Boolean.TRUE, sortOrder));
        log.info("worklog 场景种子已创建: code={}", code);
    }
}
