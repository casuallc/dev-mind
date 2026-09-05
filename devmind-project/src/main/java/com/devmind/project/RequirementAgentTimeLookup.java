package com.devmind.project;

import java.util.Collection;
import java.util.Map;

/**
 * 需求 AI 实际耗时查找端口（CAP-27；project → session 反向依赖解耦）：
 * RequirementView 组装时批量带出 agent 会话时长汇总（秒）。
 * session 模块提供实现（SessionAgentTimeLookup）；缺席时视图为 null。
 */
public interface RequirementAgentTimeLookup {

    /** 批量反查：key=requirementId，值为会话时长汇总（秒）；无会话的 id 不在返回 map 中 */
    Map<String, Long> secondsFor(Collection<String> requirementIds);
}
