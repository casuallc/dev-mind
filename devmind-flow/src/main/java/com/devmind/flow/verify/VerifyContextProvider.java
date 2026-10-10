package com.devmind.flow.verify;

import com.devmind.common.agent.exec.ContextAssemblyRequest;
import com.devmind.common.agent.exec.ContextContribution;
import com.devmind.common.agent.exec.ContextPackage;
import com.devmind.common.agent.exec.ContextProvider;
import com.devmind.common.agent.exec.ManifestItem;
import com.devmind.common.verify.VerifyTokenService;
import com.devmind.project.ProjectService;
import com.devmind.project.RequirementService;
import com.devmind.project.dto.ProjectView;
import com.devmind.project.model.RequirementEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CAP-73 FR-04 agent 自主触发通道装配 provider（节序 60，附件 40/会话附件 50 之后）：
 * 会话挂需求且该需求已绑定验证环境、项目已开「开发中验证」时，把 devmind-verify 助手脚本
 * （POSIX sh + Windows .bat 双份，内嵌 verify 作用域 token）物化进会话工作区
 * {@code .devmind/input/verify/}，并在注入块广告用法与时机。
 *
 * <p><b>瘦上下文例外</b>：CAP-52 的执行会话（lean）砍的是知识/附件等「自动命中注入」，
 * 而验证链的受用者恰恰就是需求级开发会话——本 provider 不看 lean，只看需求绑定与项目开关
 * （SessionContextService 因此对 lean 会话也透传 requirementId）。</p>
 *
 * <p>token 绑 sessionId+requirementId（{@link VerifyTokenService}，过期兜底 + 触发端会话
 * 存活检查）；重建路径（TTL 过期 runner 重拉）重跑装配即重签，自然续期。预览（dryRun）与
 * 无会话 id 的兼容路径不签发。</p>
 *
 * <p>服务端公网基址 {@code devmind.verify.server-url} 缺失时不物化（log 告警）——脚本无法
 * 猜出反向代理后的回连地址，物化一个必失败的脚本比没有更糟。</p>
 */
@Component
@Order(60)
public class VerifyContextProvider implements ContextProvider {

    private static final Logger log = LoggerFactory.getLogger(VerifyContextProvider.class);

    /** 物化相对路径（.devmind/input/ 下；.devmind 整目录已在平台 info/exclude 排除集） */
    static final String SCRIPT_PATH = "verify/devmind-verify";
    static final String SCRIPT_PATH_BAT = "verify/devmind-verify.bat";

    private final RequirementService requirementService;
    private final ProjectService projectService;
    private final VerifyProperties props;
    private final VerifyTokenService tokenService;

    public VerifyContextProvider(RequirementService requirementService, ProjectService projectService,
                                 VerifyProperties props, VerifyTokenService tokenService) {
        this.requirementService = requirementService;
        this.projectService = projectService;
        this.props = props;
        this.tokenService = tokenService;
    }

    @Override
    public ContextContribution contribute(ContextAssemblyRequest req) {
        if (req.dryRun() || req.sessionId() == null || req.sessionId().isBlank()
                || req.requirementId() == null || req.requirementId().isBlank()) {
            return ContextContribution.empty();
        }
        RequirementEntity requirement;
        try {
            requirement = requirementService.requireById(req.requirementId());
        } catch (Exception e) {
            log.warn("verify 助手装配：需求读取失败（跳过）: {} err={}", req.requirementId(), e.getMessage());
            return ContextContribution.empty();
        }
        if (requirement.getVerifyEnvironmentId() == null) {
            return ContextContribution.empty(); // 未绑定验证环境 = 无验证链，静默跳过（常态）
        }
        ProjectView project = projectService.get(requirement.getProjectId());
        if (project == null || !project.verifyInDevEnabled()) {
            return ContextContribution.empty(); // 项目开关关闭 = 不物化（触发端也会 403，双重一致）
        }
        String base = props.getServerUrl() == null ? "" : props.getServerUrl().strip()
                .replaceAll("/+$", "");
        if (base.isBlank()) {
            log.warn("verify 助手装配：devmind.verify.server-url 未配置，跳过物化（requirement={}）",
                    req.requirementId());
            return ContextContribution.empty();
        }
        String token = tokenService.mint(req.sessionId(), req.requirementId(),
                Instant.now().plus(props.getTokenTtlHours(), ChronoUnit.HOURS));
        String url = base + "/api/verify/trigger?token=" + token;

        List<ContextPackage.InputFile> inputs = List.of(
                new ContextPackage.InputFile(SCRIPT_PATH, "devmind-verify", "text/x-sh",
                        b64(renderSh(url))),
                new ContextPackage.InputFile(SCRIPT_PATH_BAT, "devmind-verify.bat",
                        "application/x-bat", b64(renderBat(url))));
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("path", ".devmind/input/" + SCRIPT_PATH);
        extra.put("tokenTtlHours", props.getTokenTtlHours());
        ManifestItem item = new ManifestItem(ManifestItem.KIND_TOOL, req.requirementId(),
                "devmind-verify 验证触发助手", null, ManifestItem.SOURCE_REQUEST, extra);

        String section = "\n---\n\n## 开发中验证\n\n"
                + "本需求已绑定验证环境。完成一个**可验证的改动并 commit** 后，用 Bash 触发"
                + "「推送→增量构建→环境补丁→冒烟」全链验证：\n\n"
                + "```bash\n"
                + "bash .devmind/input/" + SCRIPT_PATH + " --note \"改了什么\"\n"
                + "bash .devmind/input/" + SCRIPT_PATH + " --note \"改动说明\" --modules \"user-service,common\"\n"
                + "```\n\n"
                + "- 受理后立即返回 runId，**不要轮询等待**——验证结果会自动回灌本会话；\n"
                + "- 只改了部分模块时带 `--modules`（编译范围由你按本次改动判断；不传 = 全量构建，永远正确）；\n"
                + "- 被拒时输出结构化错误码（如 VERIFY_NO_NEW_COMMIT = 需求分支无新提交——先 commit 再触发），"
                + "按错误码引导处理，不要盲目重试。\n";
        log.info("verify 助手装配: session={} requirement={} ttlHours={}",
                req.sessionId(), req.requirementId(), props.getTokenTtlHours());
        return new ContextContribution(List.of(section), List.of(), List.of(), null,
                List.of(item), inputs);
    }

    /** POSIX sh 助手（Windows 节点经 git bash 同样可用——runner execShell 恒为 sh 系）。 */
    static String renderSh(String url) {
        return """
                #!/bin/sh
                # Dev-Mind 验证链触发助手（CAP-73，平台物化生成，勿手改；token 绑本会话+需求，会话结束即失效）
                # 用法: devmind-verify [--note "验证备注"] [--modules "user-service,common"]
                set -u
                URL='%s'
                NOTE=""
                MODULES=""
                while [ $# -gt 0 ]; do
                  case "$1" in
                    --note) NOTE="${2:-}"; shift 2 ;;
                    --modules) MODULES="${2:-}"; shift 2 ;;
                    *) echo "未知参数: $1（用法: devmind-verify [--note \\"...\\"] [--modules \\"a,b\\"]）" >&2; exit 2 ;;
                  esac
                done
                esc() { printf '%%s' "$1" | tr -d '\\r\\n' | sed -e 's/\\\\/\\\\\\\\/g' -e 's/"/\\\\"/g'; }
                BODY=$(printf '{"note":"%%s","modules":"%%s"}' "$(esc "$NOTE")" "$(esc "$MODULES")")
                RESP=".devmind-verify-resp.$$"
                CODE=$(curl -sS -o "$RESP" -w '%%{http_code}' -X POST "$URL" \\
                    -H 'Content-Type: application/json' -d "$BODY" 2>&1) \\
                  || { echo "无法连接 Dev-Mind 服务端: $CODE" >&2; rm -f "$RESP"; exit 1; }
                cat "$RESP"; echo
                rm -f "$RESP"
                case "$CODE" in
                  2*) echo "验证已受理（结果将自动回灌本会话，无需等待）。" ;;
                  *) echo "验证触发被拒（HTTP $CODE），请按上方错误码引导处理。" >&2; exit 1 ;;
                esac
                """.formatted(url);
    }

    /** Windows .bat 助手（纯 ASCII 文案——cmd 按 GBK 解析 .bat，中文会乱码；首选仍是 bash 版）。 */
    static String renderBat(String url) {
        return """
                @echo off
                rem Dev-Mind verify trigger helper (CAP-73, platform materialized, do not edit).
                rem Usage: devmind-verify.bat [--note "text"] [--modules "a,b"]
                setlocal
                set "NOTE="
                set "MODULES="
                :parse
                if "%%~1"=="" goto run
                if "%%~1"=="--note" (set "NOTE=%%~2" & shift & shift & goto parse)
                if "%%~1"=="--modules" (set "MODULES=%%~2" & shift & shift & goto parse)
                echo Unknown argument: %%~1 1>&2
                exit /b 2
                :run
                curl -sS -X POST "%s" -H "Content-Type: application/json" ^
                    -d "{\\"note\\":\\"%%NOTE%%\\",\\"modules\\":\\"%%MODULES%%\\"}"
                echo.
                """.formatted(url);
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
