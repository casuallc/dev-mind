package com.devmind.common.model;

import java.nio.file.Path;
import java.util.Optional;

/**
 * CAP-57 分类服务安装包查询 SPI（common 定义，devmind-classify 实现，devmind-agent 的
 * {@code GET /api/agent/classify/packages/{id}} 下载端点经 ObjectProvider 探测注入消费
 * ——agent 模块不反向依赖 classify）。先例：{@code DecisionLabBundleProvider}（CAP-56）。
 *
 * <p><b>流式契约</b>：实现方返回磁盘 {@link Path}，端点用 FileSystemResource 流式响应；
 * GB 级模型权重包禁读成 byte[] 进内存（{@code getBytes()} 在 RunnerPackageService 是
 * 64MB runner jar 的特例，不能照抄到这里）。</p>
 *
 * <p>未装配（classify 模块没上线 / 滚动升级中）= 端点 404 = 节点侧该次安装失败并说清原因，
 * 不静默降级。</p>
 */
public interface ClassifyPackageProvider {

    /**
     * @param id 安装包 id（classify_packages 主键）
     * @return 包文件定位（含 sha256/size 供响应头与日志）；包不存在/文件丢失 → empty（端点 404）
     */
    Optional<ClassifyPackageFile> packageFile(long id);

    /**
     * @param fileName 原始文件名（Content-Disposition 与节点日志用）
     * @param path     服务端磁盘路径（端点流式读出，禁 byte[]）
     * @param sha256   包文件 sha256（hex；响应头附带，runner 侧下载后复核）
     * @param sizeBytes 包文件大小（Content-Length）
     */
    record ClassifyPackageFile(String fileName, Path path, String sha256, long sizeBytes) {
    }
}
