package com.devmind.session.dto;

/**
 * CAP-42 手动收口请求。布尔字段用包装类型（Jackson 3 红线：primitive 收到 null 直接抛错）。
 *
 * @param discardChanges 收口前丢弃未提交改动（reset --hard + clean -fd；只清未提交脏文件，
 *                       不解提交级合并冲突）；null/false = 脏工作区直接失败保留现场
 */
public record FinalizeRequest(Boolean discardChanges) {

    public boolean effectiveDiscardChanges() {
        return Boolean.TRUE.equals(discardChanges);
    }
}
