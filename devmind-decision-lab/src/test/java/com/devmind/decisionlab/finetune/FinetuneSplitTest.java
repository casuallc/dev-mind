package com.devmind.decisionlab.finetune;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-56 训练/验证切分：判据会入库、会照发进执行包，所以这里要钉住的不是"切得漂亮"，
 * 而是<b>"切得能被复述"</b>——同一份集同一个种子切出同一个结果（否则报告里的 val 指标
 * 换了样本都没人知道），两边无交集且覆盖全部样本（否则有样本既没训也没验，静默消失）。
 */
class FinetuneSplitTest {

    private static List<Long> ids(int n) {
        return LongStream.rangeClosed(1, n).boxed().toList();
    }

    @Test
    void sameSeedOnTheSameSetGivesTheSameSplit() {
        FinetuneSplit.Split a = FinetuneSplit.of(ids(20), 42L, 0.8);
        FinetuneSplit.Split b = FinetuneSplit.of(ids(20), 42L, 0.8);

        assertEquals(a.trainIds(), b.trainIds());
        assertEquals(a.valIds(), b.valIds());
        // 输入顺序不该影响结果：同一次实验换个查询顺序重放，切分必须还是那一份
        List<Long> reversed = new ArrayList<>(ids(20));
        java.util.Collections.reverse(reversed);
        assertEquals(a.valIds(), FinetuneSplit.of(reversed, 42L, 0.8).valIds());
    }

    @Test
    void theSeedActuallyMovesTheBoundary() {
        Set<List<Long>> seen = new HashSet<>();
        for (long seed = 1; seed <= 20; seed++) {
            seen.add(FinetuneSplit.of(ids(20), seed, 0.8).valIds());
        }
        assertTrue(seen.size() > 1, "种子不改变切分 = 种子是摆设，而它已经写进库里当判据了");

        // 还要证明它**不是**"按顺序取尾巴"：那种实现能通过本类其它所有断言，
        // 而样本顺序里只要带一点系统偏差（比如同一批收编的记录挨在一起），
        // 偏差就会整批地落进验证集——指标看着正常，考的却全是一类样本
        List<Long> tail = ids(20).subList(20 - 4, 20);
        assertTrue(seen.stream().anyMatch(v -> !v.equals(tail)),
                "所有种子都切出『最后的 4 条』：这不是随机切分，是按顺序切");
    }

    @Test
    void bothSidesAreDisjointAndCoverEverything() {
        for (int n : new int[] {2, 3, 7, 60, 100}) {
            for (double ratio : new double[] {0.5, 0.8, 0.95}) {
                FinetuneSplit.Split s = FinetuneSplit.of(ids(n), 7L, ratio);
                Set<Long> union = new HashSet<>(s.trainIds());
                int trainSize = union.size();
                union.addAll(s.valIds());
                assertEquals(n, union.size(),
                        "n=" + n + " ratio=" + ratio + "：有样本既没进训练也没进验证（或重复）");
                assertEquals(trainSize + s.valIds().size(), union.size(), "两边不能有交集");
            }
        }
    }

    /** 两边各自升序：shuffle 后的顺序只是中间产物，入库与进 payload 的都要好读、好 diff */
    @Test
    void bothSidesComeBackSorted() {
        FinetuneSplit.Split s = FinetuneSplit.of(ids(30), 5L, 0.8);
        assertEquals(s.trainIds(), s.trainIds().stream().sorted().toList());
        assertEquals(s.valIds(), s.valIds().stream().sorted().toList());
    }

    /** 验证集条数按占比四舍五入，但两边都至少留 1 条——切空任何一边，这次实验就少了一半信息 */
    @Test
    void valCountFollowsTheRatioButNeverEmptiesASide() {
        assertEquals(2, FinetuneSplit.of(ids(10), 1L, 0.8).valCount());
        assertEquals(5, FinetuneSplit.of(ids(10), 1L, 0.5).valCount());
        assertEquals(1, FinetuneSplit.of(ids(2), 1L, 0.8).valCount(), "2 条样本也要切得出验证集");
        assertEquals(1, FinetuneSplit.of(ids(3), 1L, 0.95).valCount());
        assertEquals(1, FinetuneSplit.of(ids(3), 1L, 0.1).trainCount(), "训练侧至少 1 条");
    }

    @Test
    void fewerThanTwoItemsIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> FinetuneSplit.of(List.of(), 1L, 0.8));
        assertThrows(IllegalArgumentException.class, () -> FinetuneSplit.of(List.of(9L), 1L, 0.8));
    }

    /** 两端都不允许：0 = 没有训练样本，1 = 没有验证样本（服务端据此在触发时就拒绝） */
    @Test
    void ratioRangeExcludesBothEnds() {
        assertTrue(FinetuneSplit.ratioInRange(0.1));
        assertTrue(FinetuneSplit.ratioInRange(0.8));
        assertTrue(FinetuneSplit.ratioInRange(0.95));
        assertFalse(FinetuneSplit.ratioInRange(0.0));
        assertFalse(FinetuneSplit.ratioInRange(0.05));
        assertFalse(FinetuneSplit.ratioInRange(0.99));
        assertFalse(FinetuneSplit.ratioInRange(1.0));
    }
}
