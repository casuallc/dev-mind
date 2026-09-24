package com.devmind.bookmark.dto;

/**
 * FR-05 账号整组提交的单条入参。
 *
 * @param id            已有账号的 id；新建为 null（缺失即视为新增，未出现的已有账号即删除）
 * @param label         账号用途标签（如「管理员」「只读账号」）
 * @param username      登录名
 * @param password      新密码明文；null = 不修改（编辑态留空语义）
 * @param clearPassword 显式清除已有密码（Jackson 3：布尔入参必须用包装类型）
 * @param note          备注
 * @param sortOrder     排序号
 */
public record BookmarkAccountRequest(
        String id,
        String label,
        String username,
        String password,
        Boolean clearPassword,
        String note,
        Integer sortOrder) {
}
