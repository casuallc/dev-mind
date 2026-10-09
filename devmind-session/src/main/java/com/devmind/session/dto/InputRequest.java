package com.devmind.session.dto;

import java.util.List;

/**
 * 注入输入：text 与 quickReply 二选一。
 * CAP-68：可携带附件引用（images=图片直读 / files=文件落盘工作区，均指向 CAP-32 附件模块）。
 */
public record InputRequest(String text, String quickReply,
                           List<AttachmentRef> images, List<AttachmentRef> files) {

    public String effectiveText() {
        if (text != null && !text.isBlank()) {
            return text;
        }
        return quickReply;
    }
}
