package com.devmind.knowledge.ingest;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * CAP-84 FR-02 ZIP 安全展开：拒绝 Zip Slip（绝对路径/..）、嵌套 ZIP、压缩炸弹
 * （解压比/总量/条目数超限），只允许白名单扩展名。
 */
public final class ZipGuard {

    public record Extracted(String relativePath, Path path, long sizeBytes) {
    }

    private ZipGuard() {
    }

    public static List<Extracted> extract(Path zipPath, Path destDir,
                                          int maxFiles, long maxTotalBytes,
                                          int maxRatio, ImportLimits limits) throws IOException {
        Files.createDirectories(destDir);
        Path destRoot = destDir.toAbsolutePath().normalize();
        List<Extracted> out = new ArrayList<>();
        long total = 0;
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = safeName(entry.getName());
                if (name == null) {
                    throw bad("ZIP 内路径非法: " + entry.getName());
                }
                if (name.isBlank()) {
                    continue;
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.startsWith("__macosx/") || lower.endsWith(".ds_store")) {
                    continue;
                }
                if (lower.endsWith(".zip")) {
                    throw bad("ZIP 内不允许嵌套 ZIP: " + name);
                }
                if (out.size() >= maxFiles) {
                    throw bad("ZIP 内文件数超过上限 " + maxFiles);
                }
                if (!limits.allowed(lower)) {
                    throw bad("ZIP 内包含不支持的文件类型: " + name);
                }
                long declared = entry.getSize();
                long compressed = entry.getCompressedSize();
                if (declared > limits.maxFileBytes()) {
                    throw bad("ZIP 内单文件超过上限: " + name);
                }
                if (declared > 0 && compressed > 0 && declared / Math.max(compressed, 1) > maxRatio) {
                    throw bad("ZIP 内文件压缩比异常（疑似压缩炸弹）: " + name);
                }
                Path target = destRoot.resolve(name).normalize();
                if (!target.startsWith(destRoot)) {
                    throw bad("ZIP 内路径非法: " + name);
                }
                Files.createDirectories(target.getParent());
                long written = copyCapped(zip.getInputStream(entry), target, maxTotalBytes - total);
                total += written;
                if (total > maxTotalBytes) {
                    throw bad("ZIP 解压后总大小超过上限 " + (maxTotalBytes / 1024 / 1024) + "MB");
                }
                out.add(new Extracted(name, target, written));
            }
        }
        if (out.isEmpty()) {
            throw bad("ZIP 内没有可导入的文件");
        }
        return out;
    }

    private static long copyCapped(InputStream in, Path target, long remaining) throws IOException {
        long total = 0;
        try (InputStream src = in; OutputStream dst = Files.newOutputStream(target)) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = src.read(buf)) >= 0) {
                total += n;
                if (total > remaining) {
                    throw bad("ZIP 解压后总大小超过上限");
                }
                dst.write(buf, 0, n);
            }
        }
        return total;
    }

    /** 统一为正斜杠的包内相对路径；非法（绝对路径/.. /盘符）返回 null。 */
    private static String safeName(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.replace('\\', '/').strip();
        if (name.isEmpty() || name.startsWith("/") || name.contains("\u0000")) {
            return null;
        }
        if (name.length() >= 2 && name.charAt(1) == ':') {
            return null;
        }
        for (String segment : name.split("/")) {
            if (segment.equals("..")) {
                return null;
            }
        }
        return name;
    }

    private static DevMindException bad(String message) {
        return new DevMindException(ErrorCode.BAD_REQUEST, message);
    }

    /** 与上传服务共享的扩展名白名单/大小口径。 */
    public record ImportLimits(long maxFileBytes, java.util.Set<String> allowedExtensions) {

        public boolean allowed(String lowerName) {
            int dot = lowerName.lastIndexOf('.');
            if (dot < 0) {
                return false;
            }
            return allowedExtensions.contains(lowerName.substring(dot + 1));
        }
    }
}
