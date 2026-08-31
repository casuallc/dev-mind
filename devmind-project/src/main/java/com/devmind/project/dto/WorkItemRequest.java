package com.devmind.project.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 工作单元创建/更新请求。type 缺省 DEVELOPMENT；branchSlug 缺省时由 title 生成（分支 wi/<seq>-<slug>）。
 */
public record WorkItemRequest(
        String type,
        @NotBlank String title,
        String spec,
        String designId,
        String ownerId,
        String branchSlug) {
}
