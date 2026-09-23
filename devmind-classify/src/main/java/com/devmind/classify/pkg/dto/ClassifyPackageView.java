package com.devmind.classify.pkg.dto;

import java.time.Instant;

/** CAP-57 安装包视图（stored_path 是服务端本机路径，不下发给前端） */
public record ClassifyPackageView(Long id, String kind, String name, String pkgVersion, String sha256,
                                  Long sizeBytes, String originalFilename, String uploadedBy,
                                  Instant uploadedAt) {
}
