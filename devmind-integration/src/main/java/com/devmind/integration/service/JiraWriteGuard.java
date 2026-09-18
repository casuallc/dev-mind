package com.devmind.integration.service;

import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * CAP-47 FR-06：Jira 写路径互斥锁（进程内，与 JiraSyncService 的 running 闸同为单实例部署口径）。
 *
 * <p>保护的不变量：**同一个 Jira issue 不会被「手动推送」与「同步导入」各建一条需求**。
 * 推送侧从 {@code createIssue} 到 link 落库全程持锁——外部键由 Jira 在创建时才给出，
 * 窗口无法再收窄；同步侧由调用方把 {@code @Transactional} 的 upsertIssue **整个包在 {@link #call} 内**，
 * 因为锁必须在事务提交之后才释放：若锁在方法体的 synchronized 块里，事务提交发生在锁释放之后，
 * 并发方在提交前仍读不到新 link，锁形同虚设。
 */
@Component
public class JiraWriteGuard {

    private final Object monitor = new Object();

    /** 持锁执行并返回结果（调用方须保证 action 内部的事务边界在锁内闭合） */
    public <T> T call(Supplier<T> action) {
        synchronized (monitor) {
            return action.get();
        }
    }
}
