package com.devmind.flow.dto;

/**
 * CAP-52「开启 AI 规划 / 重新规划」请求体（全部字段可选，无 body 与空对象等价于现状直接启动）。
 *
 * @param userSuggestion 用户补充建议（可空）：服务端清洗（去控制字符、上限截断）后
 *                       拼到规划会话 taskSpec 末尾，标注为用户补充建议；null/空白时输出与不带建议完全一致
 */
public record FlowPlanRequest(String userSuggestion) {
}
