package com.devmind.decisionlab.finetune;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * CAP-56 FR-05 训练/验证切分：<b>服务端切一次、记下来、照发给节点</b>。
 *
 * <p>切分看起来是"脚本按 seed 一算就有"的东西，但它其实是这次实验的一部分事实——
 * "报告上那个 val 指标，是在哪几条样本上算的"。若让脚本现算，这条事实就只剩一个 seed 的记忆：
 * 库版本、读取顺序、切分算法的任何变化都可能让同一个 seed 切出另一组样本，而报告上
 * 数字毫无变化、没人会发现两次跑的 val 不可比。</p>
 *
 * <p><b>确定性靠 {@link Collections#shuffle(List, Random)}</b>：{@code java.util.Random} 是
 * 有明确算法的（LCG），同一个 seed 在任何 JDK 上给出同一个序列，所以切分是可复现的；
 * 而返回的 train/val <b>各自升序</b>——shuffle 后的顺序只是中间产物，
 * 入库与进 payload 的都是有序列表，好读也好 diff。</p>
 *
 * <p><b>val 至少 1 条、train 至少 1 条</b>：训练集切不出验证集 = 跑完只能看到训练损失，
 * 那正是这个 CAP 要治的"看起来在学、没人知道学得怎么样"。样本太少（&lt;2 条）直接拒绝，
 * 而不是悄悄把 val 设成空。</p>
 */
public final class FinetuneSplit {

    /** 允许的训练占比区间：两端都不能取（0 = 没有训练样本，1 = 没有验证样本） */
    static final double MIN_TRAIN_RATIO = 0.1;
    static final double MAX_TRAIN_RATIO = 0.95;

    private FinetuneSplit() {
    }

    /**
     * @param trainIds 训练样本 id（升序）
     * @param valIds   验证样本 id（升序）
     */
    public record Split(List<Long> trainIds, List<Long> valIds) {

        public int trainCount() {
            return trainIds.size();
        }

        public int valCount() {
            return valIds.size();
        }
    }

    /**
     * @param itemIds    训练集全部条目 id（顺序无关，内部会先排序再打乱）
     * @param seed       切分种子
     * @param trainRatio 训练占比（{@value #MIN_TRAIN_RATIO}~{@value #MAX_TRAIN_RATIO} 之间）
     */
    public static Split of(List<Long> itemIds, long seed, double trainRatio) {
        List<Long> ids = new ArrayList<>(itemIds);
        Collections.sort(ids);
        int n = ids.size();
        if (n < 2) {
            throw new IllegalArgumentException("切分至少需要 2 条样本，实际 " + n);
        }
        int valCount = (int) Math.round(n * (1.0 - trainRatio));
        valCount = Math.min(Math.max(valCount, 1), n - 1);

        List<Long> shuffled = new ArrayList<>(ids);
        Collections.shuffle(shuffled, new Random(seed));

        List<Long> val = new ArrayList<>(shuffled.subList(0, valCount));
        List<Long> train = new ArrayList<>(shuffled.subList(valCount, n));
        Collections.sort(train);
        Collections.sort(val);
        return new Split(List.copyOf(train), List.copyOf(val));
    }

    static boolean ratioInRange(double ratio) {
        return ratio >= MIN_TRAIN_RATIO && ratio <= MAX_TRAIN_RATIO;
    }
}
