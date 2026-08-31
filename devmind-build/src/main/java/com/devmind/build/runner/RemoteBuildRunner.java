package com.devmind.build.runner;

import com.devmind.build.model.BuildStep;
import com.devmind.serveradapter.service.ServerOperationService;
import com.devmind.serveradapter.spi.ExecResult;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * CAP-08 FR-02 远程执行器：委托 CAP-07 ServerOperationService，每步骤的 command 是
 * 目标服务器项目下的脚本模板 code（白名单，capability=build），commit/branch 作模板参数传入。
 */
@Component
public class RemoteBuildRunner {

    private final ServerOperationService serverOpService;

    public RemoteBuildRunner(ServerOperationService serverOpService) {
        this.serverOpService = serverOpService;
    }

    public StepResult runStep(Long serverId, BuildStep step, String commit, String branch,
                              Consumer<String> sink) {
        Map<String, String> params = new HashMap<>();
        if (commit != null && !commit.isBlank()) {
            params.put("commit", commit);
        }
        if (branch != null && !branch.isBlank()) {
            params.put("branch", branch);
        }
        try {
            ExecResult r = serverOpService.execute(serverId, step.command(), params, "build");
            if (r.stdout() != null && !r.stdout().isBlank()) {
                sink.accept(r.stdout().stripTrailing());
            }
            if (r.stderr() != null && !r.stderr().isBlank()) {
                sink.accept("[stderr] " + r.stderr().stripTrailing());
            }
            String err = r.stderr() == null || r.stderr().isBlank() ? "exit=" + r.exitCode() : r.stderr();
            return new StepResult(r.success(), r.exitCode(), r.success() ? null : err);
        } catch (Exception e) {
            String msg = rootMessage(e);
            sink.accept("[stderr] " + msg);
            return new StepResult(false, -1, msg);
        }
    }

    private String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
