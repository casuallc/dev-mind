package com.devmind.auth.dto;

import jakarta.validation.constraints.Size;

/** 更新角色：null/空串字段不动；baseRole 变更会同步所有使用该角色的用户。 */
public record UpdateRoleRequest(@Size(max = 64) String name, @Size(max = 512) String description,
                                String baseRole) {
}
