package com.devmind.openapi.dto;

/**
 * 签发 API Key 响应：secret 明文仅在此返回一次，服务端只存哈希，之后无法再查看。
 */
public record IssuedKeyView(
        ApiKeyView key,
        String secret) {
}
