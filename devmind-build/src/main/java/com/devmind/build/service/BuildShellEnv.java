package com.devmind.build.service;

import com.devmind.execution.model.StepSpec;

import java.util.regex.Pattern;

/**
 * 构建步骤脚本的 Node 环境初始化前导（修复构建 exit=127 / npm: command not found）：
 * 每步命令首部注入一段 POSIX sh——按优先级 source nvm/profile、兜底补 PATH，
 * 并打印 node/npm/PATH 诊断；步骤命令用到 node 系工具而 npm 仍缺失时，以可读错误
 * （exit=86，非裸 127）fail-fast，日志明确指向「节点未安装 Node 或 PATH 未生效」。
 * 只注入脚本头，不改产物路径、不改分支/commit 解析；LOCAL 与 AGENT 执行路径共用
 * （两侧都把 step.command 写 /tmp/devmind-exec-*.sh 后由 sh 执行）。
 */
final class BuildShellEnv {

    /** npm 缺失且本步骤需要时的退出码：避开 127（shell 原生 command not found 码），便于日志与告警区分 */
    static final int EXIT_NODE_MISSING = 86;

    /** 脚本头标记：用于幂等判断（已注入过的命令不再重复包裹） */
    private static final String MARKER = "[devmind] Node 环境初始化";

    /** node 系命令探测：词边界匹配，避免误中 node_modules、frontend 这类路径/目录名 */
    private static final Pattern NODE_CMD = Pattern.compile("\\b(npm|npx|node|yarn|pnpm)\\b");

    /** 环境初始化 + 诊断输出（所有构建步骤共用） */
    private static final String PRELUDE = """
            # ---- [devmind] Node 环境初始化与诊断（非交互式 shell 下保证 npm 可见） ----
            __dm_sourced=""
            for __dm_f in /etc/profile.d/nvm.sh "$HOME/.nvm/nvm.sh" /etc/profile; do
              if [ -f "$__dm_f" ]; then
                . "$__dm_f" >/dev/null 2>&1 || true
                __dm_sourced="$__dm_sourced $__dm_f"
              fi
            done
            # 兜底 PATH：nvm 默认版本目录（glob 末位≈最新版本，逐次前置后居首）+ 常见安装目录
            if [ -d "$HOME/.nvm/versions/node" ]; then
              for __dm_v in "$HOME/.nvm/versions/node"/*/bin; do
                if [ -d "$__dm_v" ]; then PATH="$__dm_v:$PATH"; fi
              done
            fi
            for __dm_d in /usr/local/bin /usr/bin; do
              case ":$PATH:" in *":$__dm_d:"*) ;; *) PATH="$PATH:$__dm_d" ;; esac
            done
            export PATH
            echo "[env] 初始化来源:${__dm_sourced:- 无（未命中任何 nvm/profile 脚本）}"
            echo "[env] PATH=$PATH"
            if command -v node >/dev/null 2>&1; then echo "[env] node=$(command -v node) 版本=$(node -v 2>&1)"; else echo "[env] node=<未找到>"; fi
            if command -v npm >/dev/null 2>&1; then echo "[env] npm=$(command -v npm) 版本=$(npm -v 2>&1)"; else echo "[env] npm=<未找到>"; fi
            """;

    /** npm 缺失处理（硬失败版）：步骤命令显式使用 node 系工具时注入，fail-fast 且给出可定位诊断 */
    private static final String GUARD_REQUIRE = """
            if ! command -v npm >/dev/null 2>&1; then
              echo "[env][ERROR] 未找到 npm：执行节点未安装 Node.js，或 Node 环境未在非交互式 shell 的 PATH 中生效" >&2
              echo "[env][ERROR] 已尝试初始化: /etc/profile.d/nvm.sh, $HOME/.nvm/nvm.sh, /etc/profile, $HOME/.nvm/versions/node/*/bin, /usr/local/bin, /usr/bin" >&2
              echo "[env][ERROR] 请在执行节点安装 Node（或配置 nvm 并写入非交互式 profile）后重跑；exit=86 表示节点缺 Node 环境，非脚本错误" >&2
              exit 86
            fi
            """;

    /** npm 缺失处理（告警版）：本步骤未直接使用 node 系命令，仅提示不阻断 */
    private static final String GUARD_WARN = """
            if ! command -v npm >/dev/null 2>&1; then
              echo "[env][WARN] 未找到 npm（本步骤未直接使用 node 系命令，继续执行；若后续命令间接依赖 npm 将失败）"
            fi
            """;

    private BuildShellEnv() {
    }

    /**
     * 为构建步骤命令注入 Node 环境初始化前导。
     * 命令为空或已注入过则原样返回；其余步骤一律带初始化+诊断，
     * 用到 npm/npx/node/yarn/pnpm 的步骤附加 npm 缺失硬失败守卫。
     */
    static StepSpec wrap(StepSpec step) {
        if (step == null || step.command() == null || step.command().isBlank()
                || step.command().contains(MARKER)) {
            return step;
        }
        boolean needsNode = NODE_CMD.matcher(step.command()).find();
        String command = PRELUDE + (needsNode ? GUARD_REQUIRE : GUARD_WARN) + step.command();
        return new StepSpec(step.name(), command, step.workingDir(), step.location());
    }
}
