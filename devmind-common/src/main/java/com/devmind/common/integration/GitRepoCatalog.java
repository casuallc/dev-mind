package com.devmind.common.integration;

import java.util.Collection;
import java.util.List;

/**
 * CAP-29 全局代码仓库目录（读端口）：登记表归 devmind-project 实现，
 * 消费方（如 devmind-worklog 的订阅校验与 git 扫描）经 ObjectProvider 探测注入，
 * 实现缺席时自行降级。
 */
public interface GitRepoCatalog {

    /** 全量（含 DISABLED），订阅页展示用。 */
    List<RepoRef> listAll();

    /** 按 id 批量取（订阅校验 + 扫描取库）。 */
    List<RepoRef> listByIds(Collection<Long> ids);

    /**
     * @param localPath   服务端路径：CLONE 行为服务端克隆目录，LOCAL 行为登记的本机路径
     * @param cloneStatus NONE/CLONING/READY/FAILED（LOCAL 行恒 NONE）；扫描方应跳过 CLONE 且非 READY 的行
     */
    record RepoRef(long id, String name, String localPath, String remoteUrl,
                   String defaultBranch, String status, String cloneStatus) {}
}
