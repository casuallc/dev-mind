package com.devmind.session.dto;

/**
 * 注入输入：text 与 quickReply 二选一。
 */
public record InputRequest(String text, String quickReply) {

    public String effectiveText() {
        if (text != null && !text.isBlank()) {
            return text;
        }
        return quickReply;
    }
}
