package com.devmind.task.api;

/**
 * CAP-80 FR-01 异步任务处理器 SPI：业务模块以实现类注册为 Spring bean，框架按 {@link #type()} 分发。
 *
 * <p>契约：
 * <ul>
 *   <li>{@code type()} 全局唯一——同 type 多 bean 注册启动期 fail-fast；</li>
 *   <li>{@code execute} 须<b>响应线程中断</b>（或周期检查 {@link TaskContext#isCancelled()}）：
 *       长阻塞点不理会中断时，超时/取消只能等僵尸收割兜底（FR-09）；</li>
 *   <li>抛 {@link NonRetryableTaskException} 直接终态不重试；其余异常按任务重试策略重排。</li>
 * </ul>
 */
public interface AsyncTaskHandler {

    /** 任务类型（分发键，建议带模块前缀，如 {@code knowledge.reindex}） */
    String type();

    /** 执行体。payload 为创建时固化的 JSON 快照，handler 自行解析与校验。 */
    void execute(String payload, TaskContext ctx) throws Exception;
}
