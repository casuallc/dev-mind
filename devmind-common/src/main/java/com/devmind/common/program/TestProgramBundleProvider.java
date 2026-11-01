package com.devmind.common.program;

import java.util.Optional;

/**
 * CAP-104 M1.5 程序包下发 SPI（common 定义，devmind-test 实现，devmind-agent 的
 * {@code GET /api/agent/test-program/assets/{assetId}/bundle} 端点消费）。
 *
 * <p>程序包是上传时的不可变 ZIP；运行前由服务端复核 sha256。节点只拿节点 token 拉取，
 * 拉取端点不暴露平台文件系统路径。未装配（test 模块缺席）= 404，运行侧 fail-visible。</p>
 */
public interface TestProgramBundleProvider {

    /**
     * @param assetId 程序资产 id
     * @return ZIP 字节 + 原文件名；资产不存在/未 APPROVED/非 PROGRAM → empty
     */
    Optional<Bundle> packageBytes(long assetId);

    record Bundle(byte[] bytes, String filename, String sha256) {
    }
}
