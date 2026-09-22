package com.devmind.decisionlab.eval;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-56 评测命令渲染：这条命令要穿过节点上的 execAllowlist 逐行首 token 校验，还要自己闭合注入面。
 *
 * <p>两件事各有一组断言，谁破了都会很难查：
 * <ul>
 *   <li><b>形态</b>：单行、首 token 不加引号、脚本与数据只走环境变量（它们的路径服务端不可能知道）；</li>
 *   <li><b>注入面</b>：路径来自人在页面上填的字符串，含单引号/换行/槽位乱字符一律在渲染前拒掉——
 *       一旦拼进命令行，那就是一条能执行任意命令的命令。</li>
 * </ul>
 */
class EvalScriptTest {

    private static EvalScript.Spec spec(String checkpoint, String slot, String out,
                                        String base, String baseSlot, boolean fit) {
        return new EvalScript.Spec("python", checkpoint, slot, out, base, baseSlot, fit);
    }

    @Test
    void rendersTheWholeCommandOnOneLineWithTheBareInterpreterFirst() {
        String command = EvalScript.render(new EvalScript.Spec("/opt/laya/venv/bin/python",
                "/data/laya/typed-decisions", "typed-decisions", "/data/laya/out/eval-7",
                "/data/laya/english", "english", true));

        assertEquals("/opt/laya/venv/bin/python \"$DEVMIND_LAB_SCRIPT\" --payload \"$DEVMIND_LAB_PAYLOAD\""
                + " --checkpoint '/data/laya/typed-decisions' --slot typed-decisions"
                + " --out '/data/laya/out/eval-7'"
                + " --baseline-checkpoint '/data/laya/english' --baseline-slot english"
                + " --fit-temperature", command);
        assertFalse(command.contains("\n"), "runner 逐行校验首 token，多出一行就多一次打回的机会");
    }

    @Test
    void optionalFlagsDisappearWhenNotAsked() {
        String command = EvalScript.render(spec("/data/ckpt", "multilingual", null, null, null, false));
        assertFalse(command.contains("--out"));
        assertFalse(command.contains("--baseline-checkpoint"));
        assertFalse(command.contains("--fit-temperature"));
    }

    @Test
    void baselineSlotIsOmittedWhenTheBaselineHasNone() {
        String command = EvalScript.render(spec("/data/ckpt", "typed-decisions", null, "/data/base", null, false));
        assertTrue(command.contains("--baseline-checkpoint '/data/base'"));
        assertFalse(command.contains("--baseline-slot"), "空槽位不能渲染成一次空参调用");
    }

    @Test
    void singleQuoteInAPathIsRejectedInsteadOfEscaped() {
        // 单引号是"什么都不展开"的边界：路径里有它，边界就漏了
        DevMindException e = assertThrows(DevMindException.class,
                () -> EvalScript.render(spec("/data/it's-here", "typed-decisions", null, null, null, false)));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
    }

    @Test
    void newlineInAPathIsRejected() {
        assertThrows(DevMindException.class, () -> EvalScript.render(
                spec("/data/a\nb", "typed-decisions", null, null, null, false)));
        assertThrows(DevMindException.class, () -> EvalScript.render(
                spec("/data/ckpt", "typed-decisions", "/out\nrm -rf /", null, null, false)));
    }

    @Test
    void slotCharsetIsNarrow() {
        assertThrows(DevMindException.class,
                () -> EvalScript.render(spec("/data/ckpt", "typed decisions", null, null, null, false)));
        assertThrows(DevMindException.class,
                () -> EvalScript.render(spec("/data/ckpt", "slot;rm -rf /", null, null, null, false)));
        assertThrows(DevMindException.class,
                () -> EvalScript.render(spec("/data/ckpt", " ", null, null, null, false)));
    }

    @Test
    void missingCheckpointPathIsRejected() {
        DevMindException e = assertThrows(DevMindException.class,
                () -> EvalScript.render(spec("  ", "typed-decisions", null, null, null, false)));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        assertTrue(e.getMessage().contains("checkpoint"));
    }
}
