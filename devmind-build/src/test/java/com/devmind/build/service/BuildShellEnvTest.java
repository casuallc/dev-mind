package com.devmind.build.service;

import com.devmind.execution.model.StepSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BuildShellEnv} 单测：前导注入、node 系命令探测（硬失败/告警分流）、幂等。
 */
class BuildShellEnvTest {

    @Test
    void npmStepGetsRequireGuardAndDiagnostics() {
        StepSpec step = new StepSpec("前端构建", "cd frontend && npm ci && npm run build", null, "build");
        StepSpec wrapped = BuildShellEnv.wrap(step);

        assertEquals(step.name(), wrapped.name());
        assertEquals(step.workingDir(), wrapped.workingDir());
        assertEquals(step.location(), wrapped.location());
        String cmd = wrapped.command();
        assertTrue(cmd.contains("[devmind] Node 环境初始化"), "应注入环境初始化前导");
        assertTrue(cmd.contains("/etc/profile.d/nvm.sh"), "应按优先级 source nvm/profile");
        assertTrue(cmd.contains("$HOME/.nvm/nvm.sh"), "应 source 用户级 nvm");
        assertTrue(cmd.contains("command -v npm"), "应打印 npm 诊断");
        assertTrue(cmd.contains("command -v node"), "应打印 node 诊断");
        assertTrue(cmd.contains("echo \"[env] PATH=$PATH\""), "应打印 PATH 诊断");
        assertTrue(cmd.contains("exit " + BuildShellEnv.EXIT_NODE_MISSING), "npm 步骤缺 npm 时应以非 127 可读错误退出");
        assertFalse(cmd.contains("[env][WARN]"), "npm 步骤不应走告警分支");
        assertTrue(cmd.endsWith(step.command()), "原命令应原样保留在前导之后");
    }

    @Test
    void nonNodeStepGetsWarnOnlyGuard() {
        StepSpec step = new StepSpec("后端构建", "mvn -q -DskipTests package", null, "build");
        StepSpec wrapped = BuildShellEnv.wrap(step);

        String cmd = wrapped.command();
        assertTrue(cmd.contains("[devmind] Node 环境初始化"), "后端步骤同样套用环境初始化");
        assertTrue(cmd.contains("[env][WARN]"), "非 node 步骤缺 npm 仅告警不阻断");
        assertFalse(cmd.contains("exit " + BuildShellEnv.EXIT_NODE_MISSING), "非 node 步骤不应硬失败");
    }

    @Test
    void nodeModulesPathDoesNotTriggerRequireGuard() {
        StepSpec step = new StepSpec("打包", "cd frontend && ./node_modules/.bin/vite build", null, "build");
        StepSpec wrapped = BuildShellEnv.wrap(step);
        assertFalse(wrapped.command().contains("exit " + BuildShellEnv.EXIT_NODE_MISSING),
                "node_modules 路径不应误判为 node 命令");
    }

    @Test
    void yarnAndPnpmAreDetected() {
        for (String tool : new String[]{"yarn build", "pnpm install", "npx vite build", "node scripts/gen.js"}) {
            StepSpec wrapped = BuildShellEnv.wrap(new StepSpec("s", tool, null, "build"));
            assertTrue(wrapped.command().contains("exit " + BuildShellEnv.EXIT_NODE_MISSING),
                    tool + " 应识别为 node 系命令");
        }
    }

    @Test
    void blankOrAlreadyWrappedCommandsAreUntouched() {
        StepSpec blank = new StepSpec("s", "  ", null, "build");
        assertSame(blank, BuildShellEnv.wrap(blank));

        StepSpec once = BuildShellEnv.wrap(new StepSpec("s", "npm run build", null, "build"));
        assertSame(once, BuildShellEnv.wrap(once), "重复包裹应幂等");
    }
}
