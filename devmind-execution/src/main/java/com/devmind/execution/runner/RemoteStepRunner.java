package com.devmind.execution.runner;

import com.devmind.execution.model.StepResult;
import com.devmind.execution.model.StepSpec;
import com.devmind.serveradapter.service.ServerOperationService;
import com.devmind.serveradapter.spi.ExecResult;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 远程执行器（P0-1 统一执行底座，自 CAP-08 RemoteBuildRunner 泛化）：
 * 委托 CAP-07 ServerOperationService，step.command 是目标服务器上的脚本模板 code（白名单），
 * 模板参数与能力域（capability=build/deploy/test/…）由调用方传入。
 */
@Component
public class RemoteStepRunner {

    private final ServerOperationService serverOpService;

    public RemoteStepRunner(ServerOperationService serverOpService) {
        this.serverOpService = serverOpService;
    }

    public StepResult runStep(Long serverId, StepSpec step, Map<String, String> params,
                              String capability, Consumer<String> sink) {
        try {
            ExecResult r = serverOpService.execute(serverId, step.command(), params, capability);
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
            return StepResult.failed(-1, msg);
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
