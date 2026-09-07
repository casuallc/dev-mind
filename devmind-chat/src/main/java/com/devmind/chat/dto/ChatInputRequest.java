package com.devmind.chat.dto;

import java.util.List;

/**
 * 问答输入请求。images 为 CAP-32 图片附件引用（可空）。
 */
public record ChatInputRequest(String text, List<ImageRef> images) {

    public String effectiveText() {
        return text == null ? "" : text;
    }

    public List<ImageRef> effectiveImages() {
        return images == null ? List.of() : images;
    }
}
