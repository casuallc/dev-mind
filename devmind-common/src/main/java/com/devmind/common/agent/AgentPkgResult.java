package com.devmind.common.agent;

/**
 * CAP-57 pkg_ack 上行帧模型（runner → 服务端：安装包分发结果）。
 *
 * @param ok         安装是否成功（下载 + sha256 校验 + 解包 + 原子 rename 全过）
 * @param installDir 节点侧绝对安装路径（成功时非空，回写给 classify_package_installs.install_dir
 *                   ——CAP-56 checkpoint 登记的 sourcePath 就从这儿抄）
 * @param error      失败原因（ok=true 时空串；sha 不符/下载失败/磁盘不足等人读文案）
 */
public record AgentPkgResult(boolean ok, String installDir, String error) {

    public static AgentPkgResult ok(String installDir) {
        return new AgentPkgResult(true, installDir, "");
    }

    public static AgentPkgResult fail(String error) {
        return new AgentPkgResult(false, "", error == null ? "未知错误" : error);
    }
}
