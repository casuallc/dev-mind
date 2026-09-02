package com.devmind.skill.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 附件新增/更新请求。内容统一 Base64 传输（避免编码问题，二进制同通道）。
 * binary 不由客户端传，服务端按 contentType 推断（text/* 与常见文本 MIME 为文本，其余二进制）。
 * 更新时 path/contentBase64 均可选（只传其一即为改名或改内容）。
 */
public record SkillFileRequest(
        /** 包内相对路径，"/" 分隔；保留名 SKILL.md 与 . / .. 段会被拒绝 */
        String path,
        String contentBase64,
        /** 如 text/markdown、text/x-sh、image/png；可空 */
        String contentType) {
}
