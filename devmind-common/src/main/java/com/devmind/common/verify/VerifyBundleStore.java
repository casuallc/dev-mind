package com.devmind.common.verify;

import java.io.InputStream;

/**
 * CAP-73 FR-03 verify 产物包暂存 SPI：runner 经 HTTP 反向上传/拉取（节点 token 认证，
 * 端点在 devmind-agent 模块——与 CAP-56 决策实验室拉包同一先例），存储与
 * 「run ↔ 节点绑定」校验由 flow 模块实现。
 *
 * <p>绑定语义（安全红线）：上传只接受该 run 指定构建节点的 token，下载只接受指定
 * 环境节点的 token——节点 token 是长寿命凭证，不绑则任意注册节点可拉任意产物包。</p>
 */
public interface VerifyBundleStore {

    /**
     * 构建节点上传产物包：校验 run 存在、{@code buildNodeId == nodeId}、run 处于 BUILDING，
     * 落盘暂存（超限抛 CONFLICT「VERIFY_BUNDLE_TOO_LARGE」）。
     *
     * @param content zip 字节流（实现边读边限长，调用方不关流也由实现兜底关闭）
     */
    void storeBundle(long runId, String nodeId, InputStream content);

    /**
     * 环境节点下载产物包：校验 run 存在且 {@code envNodeId == nodeId}。
     *
     * @return 包字节流（调用方负责关闭）
     */
    InputStream openBundle(long runId, String nodeId);
}
