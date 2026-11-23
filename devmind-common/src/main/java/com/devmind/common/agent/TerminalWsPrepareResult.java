package com.devmind.common.agent;

import java.util.List;
import java.util.Map;

/**
 * CAP-115 terminal_ws_prepare 应答：runner 幂等物化终端工作区（{@code worktrees/term-<id>}）的回执。
 *
 * @param ok      物化是否成功（目录复用/新建都算成功；占用冲突/拉取失败 → false + error）
 * @param path    工作区实际本地路径（成功时非空；供服务端展示与后续终端 cwd 提示）
 * @param branch  实际检出的分支（{@code terminal/<短id>}）
 * @param repos   逐库物化结果（成功时非空：[{name, path, branch}]，供服务端回填明细）
 * @param error   失败原因（用户可读；占用冲突时含占用方信息）
 */
public record TerminalWsPrepareResult(boolean ok, String path, String branch,
                                      List<Map<String, Object>> repos, String error) {

    public static TerminalWsPrepareResult ok(String path, String branch, List<Map<String, Object>> repos) {
        return new TerminalWsPrepareResult(true, path, branch, repos, null);
    }

    public static TerminalWsPrepareResult failed(String error) {
        return new TerminalWsPrepareResult(false, null, null, null, error);
    }
}
