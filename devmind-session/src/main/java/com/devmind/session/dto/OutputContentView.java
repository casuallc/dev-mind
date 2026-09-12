package com.devmind.session.dto;

/** CAP-39：会话产出内容（GET /sessions/{id}/outputs/{fileName}）。 */
public record OutputContentView(String fileName, String content) {
}
