package com.devmind.session.dto;

/**
 * CAP-115 M2 终端工作区文件直写请求体。
 *
 * @param path          工作区相对 POSIX 路径（必填；.git/ 内部拒绝）
 * @param contentBase64 全文内容 base64（必填；解码后 ≤256KB）
 * @param expectedSha   乐观锁：读取时拿到的内容 sha256，非空且与现内容不符 → 409 不写
 */
public record TerminalWsFileWriteRequest(String path, String contentBase64, String expectedSha) {
}
