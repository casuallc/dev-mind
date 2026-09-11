package com.devmind.common.agent;

import java.util.List;

/**
 * CAP-37 FR-01 会话产出存储 SPI（common 定义，devmind-session 实现落 session_outputs 表，
 * devmind-agent 的 {@code POST /api/agent/output/{sessionId}} 上传端点经 ObjectProvider
 * 探测注入消费——agent 模块不反向依赖 session 实现）。
 *
 * <p>产出 = runner 会话进程退出前从工作区 {@code .devmind/output/} 回传的结构化文件
 * （CAP-14 输出契约：analysis.md / design.md / wi-plan.json），流程引擎在
 * session.completed 后从存储读取，不再依赖 worktree 路径（CAP-34 后恒 null）。</p>
 */
public interface SessionOutputSink {

    /**
     * 存储会话产出文件；同 sessionId+fileName 覆盖旧值（幂等，重发/重新分析安全）。
     * 实现方在返回前完成持久化（调用方据此保证时序：上传响应先于 exit 帧）。
     */
    void store(String sessionId, List<OutputFile> files);

    /** 单个产出文件：fileName 为纯文件名（不含路径），content 为 UTF-8 文本。 */
    record OutputFile(String fileName, String content) {
    }
}
