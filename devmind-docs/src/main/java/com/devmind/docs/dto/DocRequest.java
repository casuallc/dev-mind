package com.devmind.docs.dto;

import java.util.List;

/**
 * 文档创建请求（FR-01/FR-07）。
 *
 * @param kind          requirement | design | api-suite | report
 * @param requirementId 关联需求（CAP-13 主线，可为空）
 * @param workItemId    关联工作单元（CAP-13 主线，可为空）
 * @param projectId     归属项目（可为空，存在时校验）
 * @param title         标题
 * @param tags          标签
 * @param template      模板 key（requirement/design/api-suite/report），选择后 contentMd 用模板预填
 * @param contentMd     内容（template 指定时可为空）
 */
public record DocRequest(
        String kind,
        String requirementId,
        String workItemId,
        String projectId,
        String title,
        List<String> tags,
        String template,
        String contentMd) {
}
