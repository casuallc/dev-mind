package com.devmind.agent.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CAP-53 FR-03 路由注入：claude cwd 上抬到「项目+用户」工作区根、代码留在需求子目录后，
 * 用两处提示把 agent 引进代码目录干活——
 *
 * <ol>
 *   <li><b>cwd 级恒定路由文件</b> {@value #ROUTING_FILE}：内容不含任何会话特定信息
 *       （同 (项目,用户) 的并发需求会话共享 cwd，放会话特定内容必互相覆盖），
 *       每次 launch 幂等覆盖写；</li>
 *   <li><b>首条用户消息的路由前缀</b>（{@link #prefixTaskSpec}）：代码目录路径是会话特定的，
 *       只能走消息。只在 runner 本地拼接、不回写服务端——DB 的 taskSpec 与 [flow:*] 首行
 *       标记分流不受影响；resume 拉起本就不重放 taskSpec（CliProcessLauncher 既有行为）。</li>
 * </ol>
 */
final class CodeDirRouting {

    private static final Logger log = LoggerFactory.getLogger(CodeDirRouting.class);

    /** cwd 级路由文件名（与上下文注入块同名不同目录：注入块落代码目录，本文件落 cwd）。 */
    static final String ROUTING_FILE = "CLAUDE.local.md";

    /** 恒定路由文件内容（平台托管产物；任何会话写入都相同，并发覆盖安全）。 */
    private static final String ROUTING_FILE_CONTENT = """
            <!-- 由 Dev-Mind runner 自动生成（CAP-53），请勿手改本文件 -->
            # 工作区说明

            本目录是「项目 + 用户」级工作区根，**不是代码目录**：

            - 你的代码目录以会话首条消息中的【代码目录】为准（通常在 `worktrees/` 或 `work/` 下）；
            - 一切代码改动、git 命令、产出文件（如 `.devmind/output/` 等相对路径）都以代码目录
              为基准——先 `cd` 进代码目录再操作；
            - 代码目录下的 `CLAUDE.local.md` 是本会话的背景与任务说明，开始工作前先读它；
            - `main/` 与各 `*/main/` 是平台克隆缓存，**禁止**进入或改动；其他 `worktrees/*`
              子目录属于其他需求，**禁止**改动。
            """;

    private CodeDirRouting() {
    }

    /** cwd 级路由文件幂等写入（best-effort：写失败不阻断会话，首条消息前缀仍能指路）。 */
    static void writeRoutingFile(Path cwd) {
        try {
            Files.writeString(cwd.resolve(ROUTING_FILE), ROUTING_FILE_CONTENT, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("路由文件写入失败（首条消息前缀仍生效）: {} err={}", cwd, e.getMessage());
        }
    }

    /** 首条消息加【代码目录】路由前缀；taskSpec 为空时前缀本身即为完整引导。 */
    static String prefixTaskSpec(String taskSpec, String codeDirRel) {
        String prefix = "【代码目录】" + codeDirRel + "/ —— 你的启动目录是工作区根，不是代码目录。"
                + "所有代码改动、git 命令与产出文件（.devmind/output/ 等相对路径）都以代码目录为基准"
                + "（先 cd 进去）；开始工作前先读代码目录下的 CLAUDE.local.md（本会话背景与任务说明）。";
        return taskSpec == null || taskSpec.isBlank() ? prefix : prefix + "\n\n" + taskSpec;
    }

    /** 代码目录相对 cwd 的路径（统一正斜杠，Windows 的 relativize 结果是反斜杠）。 */
    static String relativize(Path cwd, Path codeDir) {
        return cwd.relativize(codeDir).toString().replace('\\', '/');
    }
}
