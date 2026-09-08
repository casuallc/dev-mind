package com.devmind.common.agent.exec;

/**
 * CAP-33 FR-02 上下文资产供给 SPI（common 定义，knowledge/docs/skill 模块各出一个实现）：
 * 装配管线（devmind-session 的 ContextAssembler）按 SPI 收集，不反向依赖实现——
 * 与 CAP-34 的 {@link com.devmind.common.agent.ContextPackageProvider} 同构。
 *
 * <p>实现要求：①③层显式 ids 严格校验（未知 id 抛 NOT_FOUND，与 skill export 同语义）；
 * ②层自动命中由实现自行识别并在 {@link ManifestItem#source} 标注；
 * {@code dryRun=true} 时禁任何副作用（hitCount 累计等）。</p>
 */
public interface ContextProvider {

    ContextContribution contribute(ContextAssemblyRequest req);
}
