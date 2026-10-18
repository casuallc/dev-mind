package com.devmind.copilot.service;

import com.devmind.auth.security.DevMindPrincipal;
import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolCall;
import com.devmind.common.agent.tool.ToolContext;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.agent.tool.ToolSpec;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.copilot.config.CopilotProperties;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 工具执行器（CAP-76 FR-05/FR-07）：合成 actor 的 SecurityContext、按工具超时执行、异常统一映射，
 * 禁止把堆栈原文回给模型。
 */
@Component
public class CopilotToolRunner {

    private static final Logger log = LoggerFactory.getLogger(CopilotToolRunner.class);

    private final CopilotProperties props;

    public CopilotToolRunner(CopilotProperties props) {
        this.props = props;
    }

    public ToolResult run(PlatformTool tool, ToolCall call, ToolContext ctx) {
        ToolSpec spec = tool.spec();
        int timeout = spec.timeoutSeconds() > 0 ? spec.timeoutSeconds() : props.getToolTimeoutSeconds();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new DevMindPrincipal(ctx.actor(), ctx.role()), null, List.of()));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ToolResult> future = executor.submit(() -> tool.execute(call, ctx));
            return future.get(timeout, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // 超时不等于未执行：投递/构建类工具可能已受理，回执必须说明
            return ToolResult.fail(ToolResult.TIMEOUT,
                    "工具执行超时（可能已提交，请到平台确认状态，勿直接重试）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.fail(ToolResult.TIMEOUT, "工具执行被中断");
        } catch (ExecutionException e) {
            return mapException(e.getCause());
        } catch (Exception e) {
            return mapException(e);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private ToolResult mapException(Throwable error) {
        Throwable cause = error;
        while (cause != null && !(cause instanceof DevMindException)
                && !(cause instanceof IllegalArgumentException) && cause.getCause() != null
                && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        if (cause instanceof IllegalArgumentException) {
            return ToolResult.fail(ToolResult.INVALID_ARGUMENTS, safe(cause.getMessage(), "参数不合法"));
        }
        if (cause instanceof DevMindException de) {
            ErrorCode code = de.getErrorCode();
            String message = safe(de.getMessage(), code.getMessage());
            return switch (code) {
                case NOT_FOUND -> ToolResult.fail(ToolResult.NOT_FOUND, message);
                case FORBIDDEN -> ToolResult.fail(ToolResult.PERMISSION_DENIED, message);
                case CONFLICT -> ToolResult.fail(ToolResult.CONFLICT, message);
                case BAD_REQUEST -> ToolResult.fail(ToolResult.INVALID_ARGUMENTS, message);
                case TOO_MANY_SESSIONS -> ToolResult.fail(ToolResult.CONFLICT, message);
                default -> ToolResult.fail(ToolResult.UPSTREAM_ERROR, message);
            };
        }
        log.warn("工具执行异常: {}", error == null ? "unknown" : error.toString());
        return ToolResult.fail(ToolResult.UPSTREAM_ERROR, "工具执行失败，请稍后再试或到平台查看详情");
    }

    private String safe(String message, String fallback) {
        if (message == null || message.isBlank()) {
            return fallback;
        }
        // 不作为用户可见文本暴露内部路径/堆栈
        String first = message.split("\\R", 2)[0];
        return first.length() <= 500 ? first : first.substring(0, 500) + "…";
    }
}
