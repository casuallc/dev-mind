package com.devmind.common.util;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * git CLI 执行内核（照 WorktreeManager.run 范式）：stdout/stderr 一律按 UTF-8 字节解码，
 * 绝不用平台默认 charset（Windows GBK 会把中文提交信息读成乱码）。
 * 原 devmind-worklog 私有类，CAP-29 起提升为公共工具（project 登记校验 / worklog 扫描共用）。
 */
public final class GitCli {

    private GitCli() {}

    public record Result(int exitCode, String out, String err) {}

    public static Result run(Path cwd, int timeoutSec, String... args) {
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            if (cwd != null) {
                pb.directory(cwd.toFile());
            }
            pb.redirectErrorStream(false);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new DevMindException(ErrorCode.INTERNAL, "git 命令超时: " + String.join(" ", args));
            }
            return new Result(p.exitValue(), out, err);
        } catch (IOException e) {
            throw new DevMindException(ErrorCode.INTERNAL, "git 命令执行失败: " + String.join(" ", args), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DevMindException(ErrorCode.INTERNAL, "git 命令被中断: " + String.join(" ", args));
        }
    }

    /** 校验通过或抛 BAD_REQUEST（带 git stderr 摘要）。 */
    public static void requireOk(Result r, List<String> args) {
        if (r.exitCode() != 0) {
            String detail = r.err() == null ? "" : r.err().strip();
            if (detail.length() > 200) {
                detail = detail.substring(0, 200);
            }
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "git 命令失败(" + String.join(" ", args) + "): " + detail);
        }
    }
}
