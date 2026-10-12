package com.devmind.common.usage;

import com.devmind.common.agent.runtime.TurnUsage;

/**
 * 逐回合用量账本 SPI（common 定义，devmind-usage 实现落 usage_records 表，
 * devmind-session / devmind-chat 的回合入账回调经 ObjectProvider 探测注入消费——
 * usage 模块依赖 session/chat，二者禁反向依赖 usage）。
 *
 * <p>与累计列账本互补：{@code addUsage} 只把六列累加进会话/问答行（无逐回合时间戳），
 * 本接口每回合落一行明细（发生时刻、耗时、归属快照），支撑请求日志与按小时趋势。
 * 汇总/分组/每日趋势仍读累计列，账本只管新功能，互不双计。</p>
 */
public interface UsageRecordSink {

    String SOURCE_SESSION = "SESSION";
    String SOURCE_CHAT = "CHAT";

    /**
     * 一回合结束落一条账本。实现方必须自吞异常（调用方在事件读取线程上，
     * 且自身已有账务 catch——账本失败不得打断会话事件流）。
     */
    void record(String source, String sessionId, TurnUsage usage);
}
