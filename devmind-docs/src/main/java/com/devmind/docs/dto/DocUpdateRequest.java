package com.devmind.docs.dto;

import java.util.List;

/**
 * 文档元数据更新请求（CAP-03 FR-08）。部分更新语义：
 * 字段为 null = 不修改；tags 传空列表 = 清空标签；title 传值则不可为空白。
 */
public record DocUpdateRequest(String title, List<String> tags) {
}
