package com.devmind.artifact.storage;

import com.devmind.artifact.model.ArtifactEntity;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 本地存储（默认）：制品即本机/服务器文件系统路径，resolve 原样返回绝对路径。
 */
@Component
public class LocalArtifactStorage implements ArtifactStorage {

    @Override
    public String type() {
        return ArtifactEntity.STORAGE_LOCAL;
    }

    @Override
    public String resolve(String path) {
        if (path == null || path.isBlank()) {
            return path;
        }
        try {
            return Path.of(path).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            return path; // 非本机路径（如远程服务器产物）：原样返回
        }
    }
}
