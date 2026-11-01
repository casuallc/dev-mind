package com.devmind.test.dto;

import java.time.Instant;
import java.util.List;

/** 一次性证据上传凭据：token 只返回一次，uploadUrl 为运行相对路径。 */
public record EvidenceTokenView(
        String token,
        Instant expiresAt,
        String uploadUrl,
        long maxBytes,
        List<String> allowedKinds) {
}
