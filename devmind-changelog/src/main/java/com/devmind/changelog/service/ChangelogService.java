package com.devmind.changelog.service;

import com.devmind.changelog.config.ChangelogProperties;
import com.devmind.changelog.dto.ChangelogEntryBrief;
import com.devmind.changelog.dto.ChangelogEntryView;
import com.devmind.changelog.dto.ChangelogPageView;
import com.devmind.changelog.model.ChangelogEntry;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * CAP-87 更新日志读取：定位本地 CHANGELOG.md → 按 mtime/大小缓存解析结果 → 筛选分页。
 *
 * <p>文件是唯一事实源，不落库。文件被替换后下一次请求自动重载；文件缺失返回
 * available=false 而不是异常，便于页面给出放行指引。</p>
 */
@Service
public class ChangelogService {

    private static final Logger log = LoggerFactory.getLogger(ChangelogService.class);
    private static final String FILE_NAME = "CHANGELOG.md";
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final ChangelogProperties props;
    private final ChangelogParser parser;

    private volatile Snapshot snapshot;

    public ChangelogService(ChangelogProperties props, ChangelogParser parser) {
        this.props = props;
        this.parser = parser;
    }

    public ChangelogPageView list(String keyword, String version, LocalDate from, LocalDate to,
                                  int page, int size) {
        int p = Math.max(0, page);
        int s = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        String kw = blankToNull(keyword);
        String ver = normalizeVersion(blankToNull(version));

        Snapshot snap = current();
        List<ChangelogEntry> filtered = snap.entries().stream()
                .filter(e -> matchesVersion(e, ver))
                .filter(e -> matchesDate(e, from, to))
                .filter(e -> matchesKeyword(e, kw))
                .toList();

        long total = filtered.size();
        int fromIdx = (int) Math.min((long) p * s, total);
        int toIdx = (int) Math.min(fromIdx + s, total);
        List<ChangelogEntryBrief> items = filtered.subList(fromIdx, toIdx).stream()
                .map(this::brief)
                .toList();
        return new ChangelogPageView(items, total, p, s, snap.available(), snap.sourcePath(),
                snap.loadedAt());
    }

    public ChangelogEntryView get(String version) {
        String target = normalizeVersion(blankToNull(version));
        if (target == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "版本号不能为空");
        }
        return current().entries().stream()
                .filter(e -> e.version().equalsIgnoreCase(target))
                .findFirst()
                .map(this::view)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND,
                        "更新日志中不存在版本 " + version));
    }

    // ---------------- 读取与缓存 ----------------

    private Snapshot current() {
        Source src = resolve();
        Snapshot cached = snapshot;
        if (cached != null && cached.matches(src)) {
            return cached;
        }
        synchronized (this) {
            cached = snapshot;
            if (cached != null && cached.matches(src)) {
                return cached;
            }
            snapshot = load(src);
            return snapshot;
        }
    }

    private Source resolve() {
        String configured = blankToNull(props.getPath());
        if (configured != null) {
            Path p = Path.of(configured);
            if (!p.isAbsolute()) {
                p = baseDir().resolve(p);
            }
            return fileSource(p);
        }
        Path root = baseDir().resolve(FILE_NAME);
        if (Files.isRegularFile(root)) {
            return fileSource(root);
        }
        Path config = baseDir().resolve("config").resolve(FILE_NAME);
        if (Files.isRegularFile(config)) {
            return fileSource(config);
        }
        return new Source(null, FILE_NAME, true, -1, -1);
    }

    private Source fileSource(Path file) {
        String display = displayOf(file);
        try {
            if (Files.isRegularFile(file)) {
                return new Source(file, display, false,
                        Files.getLastModifiedTime(file).toMillis(), Files.size(file));
            }
        } catch (IOException e) {
            log.warn("读取更新日志文件属性失败: {} ({})", display, e.getMessage());
        }
        return new Source(file, display, false, -1, -1);
    }

    private Snapshot load(Source src) {
        long maxBytes = Math.max(1, props.getMaxSizeKb()) * 1024L;
        if (src.classpath()) {
            return loadClasspath(maxBytes);
        }
        if (!src.exists()) {
            return new Snapshot(List.of(), false, src.display(), Instant.now(), -1, -1, false);
        }
        if (src.size() > maxBytes) {
            throw tooLarge(maxBytes);
        }
        try {
            String content = Files.readString(src.file(), StandardCharsets.UTF_8);
            return new Snapshot(parser.parse(content), true, src.display(), Instant.now(),
                    src.lastModified(), src.size(), false);
        } catch (IOException e) {
            log.warn("读取更新日志失败: {} ({})", src.display(), e.getMessage());
            return new Snapshot(List.of(), false, src.display(), Instant.now(),
                    src.lastModified(), src.size(), false);
        }
    }

    private Snapshot loadClasspath(long maxBytes) {
        ClassPathResource res = new ClassPathResource(FILE_NAME, getClass().getClassLoader());
        if (!res.exists()) {
            return new Snapshot(List.of(), false, FILE_NAME, Instant.now(), -1, -1, true);
        }
        try (InputStream in = res.getInputStream()) {
            int limit = (int) Math.min(Integer.MAX_VALUE - 8L, maxBytes + 1);
            byte[] bytes = in.readNBytes(limit);
            if (bytes.length > maxBytes) {
                throw tooLarge(maxBytes);
            }
            String content = new String(bytes, StandardCharsets.UTF_8);
            return new Snapshot(parser.parse(content), true, FILE_NAME, Instant.now(), -1, -1, true);
        } catch (IOException e) {
            log.warn("读取 classpath 更新日志失败: {}", e.getMessage());
            return new Snapshot(List.of(), false, FILE_NAME, Instant.now(), -1, -1, true);
        }
    }

    private DevMindException tooLarge(long maxBytes) {
        return new DevMindException(ErrorCode.BAD_REQUEST,
                "更新日志文件超过上限 " + (maxBytes / 1024) + "KB，请拆分或调整 devmind.changelog.max-size-kb");
    }

    private Path baseDir() {
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    private String displayOf(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        Path base = baseDir();
        if (abs.startsWith(base)) {
            return base.relativize(abs).toString().replace('\\', '/');
        }
        return abs.getFileName() == null ? FILE_NAME : abs.getFileName().toString();
    }

    // ---------------- 过滤与视图 ----------------

    private boolean matchesVersion(ChangelogEntry e, String version) {
        return version == null || e.version().toLowerCase(Locale.ROOT)
                .contains(version.toLowerCase(Locale.ROOT));
    }

    private boolean matchesDate(ChangelogEntry e, LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return true;
        }
        if (e.releasedAt() == null) {
            return false;
        }
        if (from != null && e.releasedAt().isBefore(from)) {
            return false;
        }
        return to == null || !e.releasedAt().isAfter(to);
    }

    private boolean matchesKeyword(ChangelogEntry e, String keyword) {
        if (keyword == null) {
            return true;
        }
        String kw = keyword.toLowerCase(Locale.ROOT);
        String haystack = String.join(" ", e.version(), nullToEmpty(e.summary()),
                nullToEmpty(e.contentMd()), String.join(" ", e.refs()), e.types().toString())
                .toLowerCase(Locale.ROOT);
        return haystack.contains(kw);
    }

    private ChangelogEntryBrief brief(ChangelogEntry e) {
        return new ChangelogEntryBrief(e.version(), e.releasedAt(), e.types(), e.refs(), e.summary());
    }

    private ChangelogEntryView view(ChangelogEntry e) {
        return new ChangelogEntryView(e.version(), e.releasedAt(), e.types(), e.refs(),
                e.summary(), e.contentMd());
    }

    private static String normalizeVersion(String version) {
        if (version == null) {
            return null;
        }
        String v = version.strip();
        if (v.length() > 1 && (v.charAt(0) == 'v' || v.charAt(0) == 'V') && Character.isDigit(v.charAt(1))) {
            return v.substring(1);
        }
        return v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private record Source(Path file, String display, boolean classpath,
                          long lastModified, long size) {
        boolean exists() {
            return file != null && lastModified >= 0;
        }
    }

    private record Snapshot(List<ChangelogEntry> entries, boolean available, String sourcePath,
                            Instant loadedAt, long lastModified, long size, boolean classpath) {
        boolean matches(Source src) {
            if (src.classpath()) {
                return classpath;
            }
            if (!src.exists()) {
                return !classpath && !available && sourcePath.equals(src.display());
            }
            return !classpath && sourcePath.equals(src.display())
                    && lastModified == src.lastModified() && size == src.size();
        }
    }
}
