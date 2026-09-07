package com.devmind.common.agent.runtime;

import com.devmind.common.agent.SessionEvent;

/**
 * CAP-30：运行时事件出口 SPI。事件落库表是各能力私产（session_events / chat_events），
 * 故 saver 不上移——各能力模块实现自己的批量落库 saver 注入运行时内核。
 */
public interface RuntimeEventSink {

    /** 事件入出口（实现方负责批量/异步落库，调用方在事件热路径上，禁阻塞）。 */
    void offer(String sessionId, SessionEvent ev);
}
