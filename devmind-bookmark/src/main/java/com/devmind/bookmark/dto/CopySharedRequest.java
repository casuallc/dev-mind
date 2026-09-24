package com.devmind.bookmark.dto;

/**
 * FR-07 把分享来的收藏复制为自己的（深拷贝条目 + 标签名，不含账号密码）。
 *
 * @param bookmarkId 来源收藏（须已分享给我）
 * @param groupId    落到我自己的哪个分组；null = 未分组
 */
public record CopySharedRequest(Long bookmarkId, Long groupId) {
}
