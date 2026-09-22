package com.devmind.decisionlab.dataset;

import com.devmind.common.decision.TriageQuestions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 题面比对：一份题面与<b>当前标准题面</b>差在哪。
 *
 * <p>CAP-56 §8 一期只服务 {@code kb-proposal-triage} 这一套题面——题面版本进指标主键，
 * 多套题面并行会让"这个数字测的是什么"失去意义。两个入口都要用这份判断：
 * 手工标注（{@code DatasetService.validate}）与从决策记录收编（{@link RecordIntake}）。
 * 分开写两份的话，某天只改了其中一份，就会出现"手写的样本拒了、回流的样本收了"这种
 * 同一条规矩两个口径的裂缝。</p>
 *
 * <p><b>不直接 equals 而是逐项比</b>：报错要说清差在哪。从历史记录回流时，记录里的题面
 * 与今天的标准题面不同是常事（题面改过一版），只说"题面不合法"会让人无从下手。</p>
 */
final class QuestionSets {

    private QuestionSets() {
    }

    /**
     * @param actual 待检查的题面（{@code {题 id: {type,instructions,criteria}}}）
     * @return 人话差异列表（空 = 与标准题面一致）
     */
    static List<String> diff(Map<String, Map<String, Object>> actual) {
        Map<String, Map<String, Object>> expected = TriageQuestions.standard();
        List<String> diff = new ArrayList<>();
        Set<String> missing = new TreeSet<>(expected.keySet());
        missing.removeAll(actual.keySet());
        Set<String> extra = new TreeSet<>(actual.keySet());
        extra.removeAll(expected.keySet());
        if (!missing.isEmpty()) {
            diff.add("缺题 " + missing);
        }
        if (!extra.isEmpty()) {
            diff.add("多题 " + extra);
        }
        for (String key : expected.keySet()) {
            Map<String, Object> want = expected.get(key);
            Map<String, Object> got = actual.get(key);
            if (got != null && !want.equals(got)) {
                diff.add("题 " + key + " 的定义不同（标准 type=" + want.get("type") + "）");
            }
        }
        return diff;
    }
}
