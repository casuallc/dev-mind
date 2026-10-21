package com.devmind.task.engine;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.task.api.AsyncTaskHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CAP-80 FR-01 分发器：收集全部 {@link AsyncTaskHandler} bean，按 type 分发。
 * 同 type 多 bean 启动期 fail-fast（静默覆盖会让任务跑到错误的执行体）。
 */
@Component
public class TaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TaskDispatcher.class);

    private final Map<String, AsyncTaskHandler> handlers;

    public TaskDispatcher(List<AsyncTaskHandler> beans) {
        Map<String, AsyncTaskHandler> map = new HashMap<>();
        for (AsyncTaskHandler h : beans) {
            AsyncTaskHandler prev = map.putIfAbsent(h.type(), h);
            if (prev != null) {
                throw new IllegalStateException("AsyncTaskHandler type 冲突: " + h.type()
                        + "（" + prev.getClass().getName() + " 与 " + h.getClass().getName() + "）");
            }
        }
        this.handlers = Map.copyOf(map);
        log.info("异步任务 handler 已注册 {} 个: {}", map.size(), map.keySet());
    }

    public boolean exists(String type) {
        return handlers.containsKey(type);
    }

    public AsyncTaskHandler handlerOrNull(String type) {
        return handlers.get(type);
    }

    /** 提交校验用：未知 type 400 */
    public void require(String type) {
        if (!handlers.containsKey(type)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "未知任务类型: " + type);
        }
    }
}
