package com.devmind.artifact.storage;

/**
 * 制品存储 SPI（P1-2）：LOCAL 默认实现；S3 等后续以同接口注册。
 * 只负责「按 path 解析为可读位置」，上传/分发后续按需扩展。
 */
public interface ArtifactStorage {

    /** 存储类型标识：LOCAL / S3 / … */
    String type();

    /** 把存储 path 解析为本进程可读的位置（本地绝对路径或 URL） */
    String resolve(String path);
}
