package com.devmind.chat.dto;

/**
 * 问答输入请求。
 */
public record ChatInputRequest(String text) {

    public String effectiveText() {
        return text == null ? "" : text;
    }
}
