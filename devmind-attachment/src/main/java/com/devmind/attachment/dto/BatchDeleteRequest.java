package com.devmind.attachment.dto;

import java.util.List;

/** CAP-68 批量删除请求。 */
public record BatchDeleteRequest(List<String> ids) {
}
