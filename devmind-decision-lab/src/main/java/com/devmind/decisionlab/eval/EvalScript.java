package com.devmind.decisionlab.eval;

import com.devmind.decisionlab.lab.LabCommands;

/**
 * CAP-56 评测命令渲染（服务端唯一负责渲染，节点只执行——延续 CAP-34「数据所有权在服务端」）。
 *
 * <p>几条形态上的硬约束，都是被下游机制逼出来的：
 * <ul>
 *   <li><b>单行</b>：runner 的 execAllowlist 逐行取首 token 校验，多一行就多一次校验，
 *       而"源码内联"这种写法必然多行。故 command 里绝不能出现换行（这里显式断言）。</li>
 *   <li><b>首个 token = pythonPath 且不加引号</b>：白名单比对的是原始首 token，
 *       加了引号的 {@code "python"} 与白名单里的 {@code python} 永远匹配不上。</li>
 *   <li><b>路径参数用单引号包</b>：路径来自人在页面上填的字符串，不能让它有机会变成命令。</li>
 *   <li><b>脚本与数据走环境变量</b>（{@code $DEVMIND_LAB_SCRIPT} / {@code $DEVMIND_LAB_PAYLOAD}）：
 *       它俩的绝对路径由 runner 拉包解包时决定，服务端<b>不可能知道</b>（临时目录在节点上）。</li>
 * </ul>
 * 引号/字符集这些规矩本身在 {@link LabCommands}（与 {@link com.devmind.decisionlab.finetune.TrainScript}
 * 共用一份——注入面只有一个写法）。</p>
 */
public final class EvalScript {

    private EvalScript() {
    }

    /**
     * @param pythonPath        节点 python 解释器（无空白单 token）
     * @param checkpointPath    被测 checkpoint 路径（节点本地目录或 HF 仓库名）
     * @param serveSlot         checkpoint 的槽位（脚本用它决定按哪套题面/原语评）
     * @param outputPath        节点上的产出目录（可空 = 只在 stdout 打报告，不在节点留文件）
     * @param baseCheckpointPath 对照基线 checkpoint（可空 = 不比对）
     * @param baseServeSlot     对照基线的槽位（可空）
     * @param fitTemperature    是否顺带做温度校准（用 held-out 切分拟合并回写 checkpoint 目录）
     */
    public record Spec(String pythonPath, String checkpointPath, String serveSlot, String outputPath,
                       String baseCheckpointPath, String baseServeSlot, boolean fitTemperature) {
    }

    /** 渲染成 runner 能执行的单行命令。 */
    public static String render(Spec spec) {
        StringBuilder sb = new StringBuilder(LabCommands.token(spec.pythonPath(), "python 解释器路径"));
        sb.append(" \"$DEVMIND_LAB_SCRIPT\" --payload \"$DEVMIND_LAB_PAYLOAD\"");
        sb.append(" --checkpoint ").append(LabCommands.quoted(spec.checkpointPath(), "checkpoint 路径"));
        sb.append(" --slot ").append(LabCommands.slot(spec.serveSlot()));
        if (notBlank(spec.outputPath())) {
            sb.append(" --out ").append(LabCommands.quoted(spec.outputPath(), "产出目录"));
        }
        if (notBlank(spec.baseCheckpointPath())) {
            sb.append(" --baseline-checkpoint ")
                    .append(LabCommands.quoted(spec.baseCheckpointPath(), "对照 checkpoint 路径"));
            if (notBlank(spec.baseServeSlot())) {
                sb.append(" --baseline-slot ").append(LabCommands.slot(spec.baseServeSlot()));
            }
        }
        if (spec.fitTemperature()) {
            sb.append(" --fit-temperature");
        }
        return LabCommands.singleLine(sb.toString());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
