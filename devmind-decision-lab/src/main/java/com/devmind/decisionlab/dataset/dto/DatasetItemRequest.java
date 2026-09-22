package com.devmind.decisionlab.dataset.dto;

import java.util.Map;

/**
 * CAP-56 标注一条样本。
 *
 * <p><b>收对象而不是收 JSON 字符串</b>：三份 JSON 由服务端序列化，前端传结构化的 map。
 * 让前端自己拼 JSON 字符串的话，一个字符的转义错误就会变成一条"看着能存、评测时解析不出"的脏样本，
 * 而这类脏数据要到跑评测才暴露。</p>
 *
 * <p>{@code questions} 可空 → 用当前标准题面（{@code TriageQuestions.standard()}）。
 * 手工标注的正常路径就是这样；只有回放历史记录时才需要显式传（那时的题面未必等于今天的）。</p>
 *
 * @param gold   人工答案原值 {题 id: 值}，与 decision_records.gold_json 同形状
 * @param caseGroup 对照组标（空 = NORMAL，认不出报 400）
 */
public record DatasetItemRequest(
        Map<String, Object> state,
        Map<String, Object> questions,
        Map<String, Object> gold,
        String caseGroup,
        String note) {
}
