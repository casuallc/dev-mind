package com.devmind.session.dto;

/**
 * CAP-42 手动收口请求。布尔字段用包装类型（Jackson 3 红线：primitive 收到 null 直接抛错）。
 *
 * @param discardChanges     收口前丢弃未提交改动（reset --hard + clean -fd；只清未提交脏文件，
 *                           不解提交级合并冲突）；null/false = 脏工作区直接失败保留现场
 * @param deleteRemoteBranch 收口成功后删除远端 feature 会话分支（协议 v19，不再 push 分支供
 *                           收口后 diff）；null/false = 维持 best-effort push 分支的现状
 */
public record FinalizeRequest(Boolean discardChanges, Boolean deleteRemoteBranch) {

    public boolean effectiveDiscardChanges() {
        return Boolean.TRUE.equals(discardChanges);
    }

    public boolean effectiveDeleteRemoteBranch() {
        return Boolean.TRUE.equals(deleteRemoteBranch);
    }
}
