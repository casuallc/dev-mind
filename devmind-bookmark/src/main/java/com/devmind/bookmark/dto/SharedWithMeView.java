package com.devmind.bookmark.dto;

import java.util.List;

/**
 * FR-07 接收方视图：分享来的分组树 + 收藏列表。
 * 收藏里的账号只含 label/username（密码字段被剔除），且不可改、不可再分享。
 *
 * @param owner 分享者 username（v1 单分享者视图按人分组展示用）
 */
public record SharedWithMeView(String owner, List<BookmarkGroupView> groups, List<BookmarkView> bookmarks) {
}
