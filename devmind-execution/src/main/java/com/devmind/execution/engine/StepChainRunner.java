package com.devmind.execution.engine;

import com.devmind.execution.model.StepResult;
import com.devmind.execution.model.StepSpec;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * 步骤链执行引擎（P0-1 统一执行底座）：顺序执行步骤快照，任一步失败即中断。
 * 只负责「边界日志 + 调度 + 聚合结果」，每步如何执行（本地/远程）由 {@link StepInvoker} 决定，
 * 业务语义（构建产物登记、部署回滚、测试汇总）留在各业务模块。
 */
@Component
public class StepChainRunner {

    /** 单步执行策略：按步骤 location/业务上下文选择本地或远程执行 */
    @FunctionalInterface
    public interface StepInvoker {
        StepResult invoke(int index, StepSpec step, Consumer<String> sink);
    }

    /** 链式执行聚合结果；ok=true 时 failedIndex=-1 */
    public record ChainResult(boolean ok, int exitCode, String error, int failedIndex) {
    }

    /**
     * @param steps      步骤快照（顺序执行）
     * @param invoker    单步执行策略
     * @param sink       日志行出口（引擎会追加步骤边界与失败行）
     * @param afterStepOk 每步成功后的回调（如步骤边界持久化日志），可为 null
     */
    public ChainResult run(List<StepSpec> steps, StepInvoker invoker, Consumer<String> sink,
                           IntConsumer afterStepOk) {
        for (int i = 0; i < steps.size(); i++) {
            StepSpec s = steps.get(i);
            String label = s.name() == null || s.name().isBlank() ? String.valueOf(i + 1) : s.name();
            sink.accept("===== 步骤 " + (i + 1) + "/" + steps.size() + " · " + label + " =====");
            StepResult r;
            try {
                r = invoker.invoke(i, s, sink);
            } catch (Exception e) {
                r = StepResult.failed(-1, "步骤执行异常: " + e.getMessage());
            }
            if (!r.ok()) {
                String err = r.error() != null ? r.error() : "exit=" + r.exitCode();
                sink.accept("[执行失败] " + err);
                return new ChainResult(false, r.exitCode(), err, i);
            }
            if (afterStepOk != null) {
                afterStepOk.accept(i);
            }
        }
        return new ChainResult(true, 0, null, -1);
    }
}
