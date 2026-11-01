package com.devmind.common.program;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * CAP-104 M1.5 程序 ZIP 的安全树形解包（runner 使用；服务端上传侧另有自己的限制）。
 *
 * <p>保留 {@code lib/} 等目录层级（Java 程序的 jar 依赖必须保持相对路径）；
 * 拒绝绝对路径、盘符、{@code ..} 逃逸；符号链接条目不落成链接（ZipInputStream 读出的是普通字节），
 * 同时限制文件数/单文件/总量，避免 zip 炸弹。</p>
 */
public final class ProgramPackage {

    private ProgramPackage() {
    }

    public static Map<String, Path> extractTree(byte[] zip, Path target) throws IOException {
        Map<String, Path> written = new LinkedHashMap<>();
        Files.createDirectories(target);
        long total = 0;
        int count = 0;
        try (InputStream in = new ByteArrayInputStream(zip); ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                count++;
                if (count > ProgramContract.MAX_PACKAGE_FILES) {
                    throw new IOException("程序包文件数超限（>" + ProgramContract.MAX_PACKAGE_FILES + "）");
                }
                String raw = entry.getName().replace('\\', '/');
                if (raw.startsWith("/") || raw.contains("..") || raw.contains(":")
                        || raw.startsWith("~")) {
                    throw new IOException("程序包含非法路径: " + raw);
                }
                Path out = target.resolve(raw).normalize();
                if (!out.startsWith(target)) {
                    throw new IOException("程序包路径穿越: " + raw);
                }
                Files.createDirectories(out.getParent());
                long fileBytes = 0;
                try (java.io.OutputStream os = Files.newOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = zin.read(buf)) > 0) {
                        fileBytes += n;
                        total += n;
                        if (fileBytes > ProgramContract.MAX_PACKAGE_FILE_BYTES) {
                            throw new IOException("程序包单文件超限（>"
                                    + ProgramContract.MAX_PACKAGE_FILE_BYTES + " 字节）: " + raw);
                        }
                        if (total > ProgramContract.MAX_PACKAGE_TOTAL_BYTES) {
                            throw new IOException("程序包解包总量超限（>"
                                    + ProgramContract.MAX_PACKAGE_TOTAL_BYTES + " 字节）");
                        }
                        os.write(buf, 0, n);
                    }
                }
                written.put(raw, out);
                zin.closeEntry();
            }
        }
        return written;
    }

    /** 包内取单文件内容（读清单用）；不存在返回 null。 */
    public static byte[] readEntry(byte[] zip, String name) throws IOException {
        try (InputStream in = new ByteArrayInputStream(zip); ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (!entry.isDirectory() && name.equals(entry.getName().replace('\\', '/'))) {
                    return readBounded(zin, ProgramContract.MAX_INPUT_BYTES);
                }
            }
        }
        return null;
    }

    private static byte[] readBounded(InputStream in, long max) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) {
                throw new IOException("程序包单文件超限（>" + max + " 字节）");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
