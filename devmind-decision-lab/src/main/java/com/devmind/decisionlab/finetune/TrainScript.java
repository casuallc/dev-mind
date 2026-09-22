package com.devmind.decisionlab.finetune;

import com.devmind.decisionlab.lab.LabCommands;
import java.util.Locale;

/**
 * CAP-56 FR-05 微调命令渲染（与 {@link com.devmind.decisionlab.eval.EvalScript} 同一形态：
 * 单行、首 token 不加引号、路径单引号包、脚本与数据走 runner 注入的环境变量）。
 *
 * <p><b>切分不进命令行</b>：train/val 的划分已经写进执行包的数据里（每条样本带 {@code split} 字段），
 * 脚本按它分流即可。若把切分留给脚本按 seed 现算，那么"这次用了哪些样本做验证"就只剩一条命令行的
 * 记忆——而框架升级、库版本变化都可能让同一个 seed 切出不同的集合，报告的 val 数字就不再可比。
 * 切分判据入库（{@code val_item_ids_json}），执行包照它发，命令行里也不必再传一遍。</p>
 *
 * <p><b>{@code --launcher} 用单引号整体包住</b>：它的值本身是一条命令
 * （如 {@code torchrun --nproc_per_node=2}），含空格；脚本侧按 shlex 语义拆开后作为子进程前缀。
 * 单引号让 shell 不解释其中的任何字符——它最终是被我们自己的脚本执行的，但没必要给中间层留口子。</p>
 */
public final class TrainScript {

    private TrainScript() {
    }

    /**
     * @param pythonPath     节点 python 解释器（无空白单 token）
     * @param baseCheckpoint 基座 checkpoint（HL 官方权重或上次微调产物）
     * @param serveSlot      服务槽位（决定题面/原语；与基座同槽——本 CAP 只换来源不改名字）
     * @param outputPath     节点上的产出目录（权重落这里，服务端只收回指纹）
     * @param epochs         训练轮数
     * @param learningRate   学习率
     * @param batchSize      批大小
     * @param seed           训练随机种子（与切分种子分开：实验可复现要能分别固定）
     * @param launcher       多卡启动前缀（可空 = 单进程；如 {@code torchrun --nproc_per_node=2}）
     */
    public record Spec(String pythonPath, String baseCheckpoint, String serveSlot, String outputPath,
                       int epochs, double learningRate, int batchSize, long seed, String launcher) {
    }

    public static String render(Spec spec) {
        StringBuilder sb = new StringBuilder(LabCommands.token(spec.pythonPath(), "python 解释器路径"));
        sb.append(" \"$DEVMIND_LAB_SCRIPT\" --payload \"$DEVMIND_LAB_PAYLOAD\"");
        sb.append(" --base-checkpoint ").append(LabCommands.quoted(spec.baseCheckpoint(), "基座 checkpoint 路径"));
        sb.append(" --slot ").append(LabCommands.slot(spec.serveSlot()));
        sb.append(" --out ").append(LabCommands.quoted(spec.outputPath(), "产出目录"));
        sb.append(" --epochs ").append(LabCommands.count(spec.epochs(), 1, MAX_EPOCHS, "训练轮数"));
        sb.append(" --lr ").append(LabCommands.decimal(spec.learningRate(), 0, MAX_LR, "学习率"));
        sb.append(" --batch ").append(LabCommands.count(spec.batchSize(), 1, MAX_BATCH, "批大小"));
        sb.append(" --seed ").append(LabCommands.count(spec.seed(), 0, MAX_SEED, "训练种子"));
        if (spec.launcher() != null && !spec.launcher().isBlank()) {
            sb.append(" --launcher ").append(LabCommands.quoted(spec.launcher(), "多卡启动前缀"));
        }
        return LabCommands.singleLine(sb.toString());
    }

    /** 上界只为挡住"手滑多打一位"（一次跑整夜的训练不值得为这种输入兜底）：1000 轮 / 1.0 学习率 */
    static final long MAX_EPOCHS = 1000;
    static final double MAX_LR = 1.0;
    static final long MAX_BATCH = 4096;
    static final long MAX_SEED = Integer.MAX_VALUE;

    /** 供日志与错误提示：一行说清"这次用什么超参跑" */
    public static String describe(Spec spec) {
        return String.format(Locale.ROOT, "epochs=%d lr=%s batch=%d seed=%d%s",
                spec.epochs(), Double.toString(spec.learningRate()), spec.batchSize(), spec.seed(),
                spec.launcher() == null || spec.launcher().isBlank() ? "" : " launcher=" + spec.launcher());
    }
}
