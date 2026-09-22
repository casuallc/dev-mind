package com.devmind.decisionlab.eval;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;

/**
 * CAP-56 评测命令渲染（服务端唯一负责渲染，节点只执行——延续 CAP-34「数据所有权在服务端」）。
 *
 * <p>几条形态上的硬约束，都是被下游机制逼出来的：
 * <ul>
 *   <li><b>单行</b>：runner 的 execAllowlist 逐行取首 token 校验，多一行就多一次校验，
 *       而"源码内联"这种写法必然多行。故 command 里绝不能出现换行（这里显式断言）。</li>
 *   <li><b>首个 token = pythonPath 且不加引号</b>：白名单比对的是原始首 token，
 *       加了引号的 {@code "python"} 与白名单里的 {@code python} 永远匹配不上。
 *       故 pythonPath 必须是无空白的单 token（{@code LabScripts.requirePython} 已在前置校验）。</li>
 *   <li><b>路径参数用单引号包</b>：单引号在 bash 里抑制一切展开，{@code $}、反引号、
 *       双引号都失效——路径来自人在页面上填的字符串，不能让它有机会变成命令。
 *       代价是路径里不能有单引号（这种路径本身也不该有），含单引号直接在渲染前拒绝。</li>
 *   <li><b>脚本与数据走环境变量</b>（{@code $DEVMIND_LAB_SCRIPT} / {@code $DEVMIND_LAB_PAYLOAD}）：
 *       它俩的绝对路径由 runner 拉包解包时决定，服务端<b>不可能知道</b>（临时目录在节点上）。
 *       这两个是固定串，用双引号让 shell 展开，且用引号包住以容忍路径里的空格。</li>
 * </ul>
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
        StringBuilder sb = new StringBuilder(spec.pythonPath().strip());
        sb.append(" \"$DEVMIND_LAB_SCRIPT\" --payload \"$DEVMIND_LAB_PAYLOAD\"");
        sb.append(" --checkpoint ").append(quoted(spec.checkpointPath(), "checkpoint 路径"));
        sb.append(" --slot ").append(slot(spec.serveSlot()));
        if (notBlank(spec.outputPath())) {
            sb.append(" --out ").append(quoted(spec.outputPath(), "产出目录"));
        }
        if (notBlank(spec.baseCheckpointPath())) {
            sb.append(" --baseline-checkpoint ").append(quoted(spec.baseCheckpointPath(), "对照 checkpoint 路径"));
            if (notBlank(spec.baseServeSlot())) {
                sb.append(" --baseline-slot ").append(slot(spec.baseServeSlot()));
            }
        }
        if (spec.fitTemperature()) {
            sb.append(" --fit-temperature");
        }
        String command = sb.toString();
        // 换行会多出一行让 runner 逐行校验（也多一次注入面），而渲染逻辑本身不该产出换行
        if (command.contains("\n") || command.contains("\r")) {
            throw new DevMindException(ErrorCode.INTERNAL, "评测命令渲染出了换行（内部错误）");
        }
        return command;
    }

    /** 单引号包裹（bash 里不展开任何东西）；含单引号/换行的值直接拒绝 */
    private static String quoted(String value, String what) {
        String v = value == null ? "" : value.strip();
        if (v.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, what + "为空");
        }
        if (v.contains("'") || v.contains("\n") || v.contains("\r") || v.contains("\0")) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    what + "含非法字符（单引号/换行）: " + v);
        }
        return "'" + v + "'";
    }

    /** 槽位名（{@code typed-decisions} 这类）：字符集收紧到路径/命令行都安全的一小撮 */
    private static String slot(String value) {
        String v = value == null ? "" : value.strip();
        if (v.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "checkpoint 槽位为空");
        }
        if (!v.matches("[A-Za-z0-9._-]+")) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "槽位名含非法字符: " + v + "（只允许字母数字与 . _ -）");
        }
        return v;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
