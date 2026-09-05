package com.devmind.common.agent;

/**
 * CAP-28 一次性 agent 任务 SPI：跑一个「发 prompt → 收结果 → 退出」的无项目裸会话，
 * 返回最终 summary。由 devmind-session 实现（headless claude）；
 * 消费方（如 devmind-worklog 日报/周报生成）以 {@code ObjectProvider<OneShotAgentRunner>}
 * 探测注入，未装配时降级（报告生成功能不可用，其余功能不受影响）。
 *
 * <p>实现约定：只读权限模式（plan），prompt 应声明"不读写任何文件，只输出正文"。</p>
 */
public interface OneShotAgentRunner {

    /**
     * 同步跑一个一次性会话，阻塞至会话结束或超时（超时杀进程并抛异常）。
     *
     * @param prompt          任务说明（素材应内联在 prompt 里，不依赖文件系统）
     * @param timeoutSeconds  超时秒数
     * @return 会话 id 与最终 summary
     */
    Result run(String prompt, int timeoutSeconds);

    /** @param sessionId 执行痕迹（会话列表可回溯）；@param summary 最终输出正文 */
    record Result(String sessionId, String summary) {}
}
