package com.devmind.decisionlab.finetune;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-56 微调命令渲染：与评测命令同一形态（单行、首 token 裸解释器、路径单引号），
 * 但要额外守住一处分寸——<b>多卡启动前缀</b>。
 *
 * <p>{@code --launcher "torchrun --nproc_per_node=2"} 的值本身是一条命令，它是这次唯一一处
 * "把一个命令塞进另一个命令"的地方，所以它整体单引号包住（bash 里不展开任何字符），
 * 由我们自己的脚本按 shlex 拆开当子进程前缀。首 token 永远是 pythonPath——
 * 换成 {@code torchrun} 当首 token 的话，节点白名单就得同时放行两套命令，
 * 而"哪条命令能跑"这件事会从一处变成两处。</p>
 */
class TrainScriptTest {

    private static TrainScript.Spec spec(String launcher) {
        return new TrainScript.Spec("/opt/laya/venv/bin/python", "/data/laya/multilingual",
                "multilingual", "/data/out/ft7", 3, 1e-4, 8, 42L, launcher);
    }

    @Test
    void rendersTheWholeCommandOnOneLineWithTheBareInterpreterFirst() {
        String command = TrainScript.render(new TrainScript.Spec("/opt/laya/venv/bin/python",
                "/data/laya/multilingual", "multilingual", "/data/out/ft7", 5, 5e-5, 16, 7L,
                "torchrun --nproc_per_node=2"));

        assertEquals("/opt/laya/venv/bin/python \"$DEVMIND_LAB_SCRIPT\" --payload \"$DEVMIND_LAB_PAYLOAD\""
                + " --base-checkpoint '/data/laya/multilingual' --slot multilingual"
                + " --out '/data/out/ft7'"
                + " --epochs 5 --lr 5.0E-5 --batch 16 --seed 7"
                + " --launcher 'torchrun --nproc_per_node=2'", command);
        assertFalse(command.contains("\n"), "runner 逐行校验首 token，多出一行就多一次打回的机会");
        assertTrue(command.startsWith("/opt/laya/venv/bin/python "),
                "首 token 必须是不加引号的解释器：execAllowlist 比对的就是它");
    }

    @Test
    void launcherIsOptionalAndNeverRenderedWhenBlank() {
        for (String launcher : new String[] {null, "", "   "}) {
            String command = TrainScript.render(spec(launcher));
            assertFalse(command.contains("--launcher"), "空启动前缀不能渲染成一次空参调用：" + command);
        }
    }

    /** 学习率用 Double.toString：指数形式里没有 shell 元字符，且不受 locale 影响（小数点永远是 .） */
    @Test
    void learningRateKeepsItsDotUnderAnyLocale() {
        assertTrue(TrainScript.render(spec(null)).contains(" --lr 1.0E-4"));
    }

    @Test
    void outOfRangeHyperparamsAreRejected() {
        assertThrows(DevMindException.class, () -> TrainScript.render(new TrainScript.Spec(
                "python", "/data/base", "multilingual", "/out", 0, 1e-4, 8, 42L, null)));
        assertThrows(DevMindException.class, () -> TrainScript.render(new TrainScript.Spec(
                "python", "/data/base", "multilingual", "/out", 3, 2.0, 8, 42L, null)));
        assertThrows(DevMindException.class, () -> TrainScript.render(new TrainScript.Spec(
                "python", "/data/base", "multilingual", "/out", 3, Double.NaN, 8, 42L, null)));
        assertThrows(DevMindException.class, () -> TrainScript.render(new TrainScript.Spec(
                "python", "/data/base", "multilingual", "/out", 3, 1e-4, 0, 42L, null)));
        assertThrows(DevMindException.class, () -> TrainScript.render(new TrainScript.Spec(
                "python", "/data/base", "multilingual", "/out", 3, 1e-4, 8, -1L, null)));
    }

    @Test
    void singleQuoteInAPathIsRejectedInsteadOfEscaped() {
        DevMindException e = assertThrows(DevMindException.class, () -> TrainScript.render(
                new TrainScript.Spec("python", "/data/it's", "multilingual", "/out", 3, 1e-4, 8, 42L, null)));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
    }

    /**
     * 启动前缀里的单引号也要拒：它被单引号包住，值里的单引号会提前闭合这个边界。
     * 一个"能自己闭合引号"的入参，就是一条能执行任意命令的入参。
     */
    @Test
    void singleQuoteInTheLauncherIsRejected() {
        assertThrows(DevMindException.class, () -> TrainScript.render(spec("torchrun --master='x'")));
        assertThrows(DevMindException.class, () -> TrainScript.render(spec("torchrun\nrm -rf /")));
    }

    @Test
    void newlineInTheOutputDirectoryIsRejected() {
        assertThrows(DevMindException.class, () -> TrainScript.render(new TrainScript.Spec(
                "python", "/data/base", "multilingual", "/out\nrm -rf /", 3, 1e-4, 8, 42L, null)));
    }

    @Test
    void slotCharsetIsNarrow() {
        assertThrows(DevMindException.class, () -> TrainScript.render(new TrainScript.Spec(
                "python", "/data/base", "typed decisions", "/out", 3, 1e-4, 8, 42L, null)));
        assertThrows(DevMindException.class, () -> TrainScript.render(new TrainScript.Spec(
                "python", "/data/base", "slot;rm -rf /", "/out", 3, 1e-4, 8, 42L, null)));
    }

    @Test
    void missingBaseCheckpointAndOutputDirectoryAreRejected() {
        assertThrows(DevMindException.class, () -> TrainScript.render(new TrainScript.Spec(
                "python", "  ", "multilingual", "/out", 3, 1e-4, 8, 42L, null)));
        DevMindException e = assertThrows(DevMindException.class, () -> TrainScript.render(
                new TrainScript.Spec("python", "/data/base", "multilingual", null, 3, 1e-4, 8, 42L, null)));
        assertTrue(e.getMessage().contains("产出目录"));
    }

    /** 日志里那一行人读摘要：超参要一眼看得出，多卡时也要说 */
    @Test
    void describeIsHumanReadable() {
        assertEquals("epochs=3 lr=1.0E-4 batch=8 seed=42", TrainScript.describe(spec(null)));
        assertEquals("epochs=3 lr=1.0E-4 batch=8 seed=42 launcher=torchrun --nproc_per_node=2",
                TrainScript.describe(spec("torchrun --nproc_per_node=2")));
    }
}
