package com.devmind.agent.runner;

import com.devmind.common.util.GitCli;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CAP-54 工作区 git 状态采集器（runner 侧）：对会话代码目录执行只读 git 命令，
 * 产出 {@code workspace_status} 帧的快照 Map。
 *
 * <p>采集口径：代码目录本身是 git 工作树（单库 / chat 沙箱 init 过的目录）→ 单条；
 * 否则扫描其<b>直接子目录</b>中带 {@code .git} 的当作各库（CAP-31 聚合根布局）。
 * worktree 的 {@code .git} 是文件（gitdir 指针）而非目录，判存一律用 {@link Files#exists}。</p>
 *
 * <p>逐库产出：branch + changes[{path, code(porcelain XY), adds, dels}]。
 * adds/dels 合并 staged（{@code --cached}）与 unstaged 两段 numstat；未跟踪文件无 numstat
 * （adds/dels 为 null）。快照内容确定性排序（按库名、路径），供调用方哈希去重。</p>
 *
 * <p>纯只读：只跑 rev-parse/status/diff；git 不存在/超时/非零退出只在该库行记 error，
 * 不拖垮整组，更不阻断会话。</p>
 */
public class GitStatusCollector {

    private static final Logger log = LoggerFactory.getLogger(GitStatusCollector.class);

    /** 单条 git 命令超时（status/diff 均为秒级操作） */
    static final int GIT_TIMEOUT_SEC = 30;

    /**
     * 采集代码目录的快照。返回 Map 可直接进 {@code workspace_status} 帧的 snapshot 字段：
     * {@code {gitAvailable, repos:[{name, branch, changes:[...], error?}], total:{files,adds,dels}, ts}}。
     */
    public Map<String, Object> collect(Path codeDir) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("ts", System.currentTimeMillis());
        List<Path> repos = findRepos(codeDir);
        if (repos.isEmpty()) {
            snapshot.put("gitAvailable", false);
            snapshot.put("repos", List.of());
            snapshot.put("total", Map.of("files", 0, "adds", 0, "dels", 0));
            return snapshot;
        }
        snapshot.put("gitAvailable", true);
        List<Map<String, Object>> repoRows = new ArrayList<>();
        int files = 0;
        int adds = 0;
        int dels = 0;
        for (Path repo : repos) {
            Map<String, Object> row = collectRepo(codeDir, repo);
            repoRows.add(row);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> changes = (List<Map<String, Object>>) row.get("changes");
            files += changes.size();
            for (Map<String, Object> c : changes) {
                if (c.get("adds") instanceof Number n) {
                    adds += n.intValue();
                }
                if (c.get("dels") instanceof Number n) {
                    dels += n.intValue();
                }
            }
        }
        snapshot.put("repos", repoRows);
        Map<String, Object> total = new LinkedHashMap<>();
        total.put("files", files);
        total.put("adds", adds);
        total.put("dels", dels);
        snapshot.put("total", total);
        return snapshot;
    }

    /** 代码目录本身是工作树 → 单库（name=""）；否则聚合根 → 各带 .git 的直接子目录为一库。 */
    static List<Path> findRepos(Path codeDir) {
        List<Path> repos = new ArrayList<>();
        if (codeDir == null || !Files.isDirectory(codeDir)) {
            return repos;
        }
        if (Files.exists(codeDir.resolve(".git"))) {
            repos.add(codeDir);
            return repos;
        }
        try (var stream = Files.list(codeDir)) {
            stream.filter(Files::isDirectory)
                    .filter(d -> Files.exists(d.resolve(".git")))
                    .sorted()
                    .forEach(repos::add);
        } catch (Exception e) {
            log.debug("扫描代码目录子库失败: {} err={}", codeDir, e.getMessage());
        }
        return repos;
    }

    private Map<String, Object> collectRepo(Path codeDir, Path repo) {
        Map<String, Object> row = new LinkedHashMap<>();
        String name = repo.equals(codeDir) ? "" : repo.getFileName().toString();
        row.put("name", name);
        row.put("changes", List.of());
        try {
            GitCli.Result branch = GitCli.run(repo, GIT_TIMEOUT_SEC,
                    "git", "rev-parse", "--abbrev-ref", "HEAD");
            row.put("branch", branch.exitCode() == 0 ? branch.out().strip() : "");
            row.put("changes", collectChanges(repo));
        } catch (Exception e) {
            row.put("error", String.valueOf(e.getMessage()));
        }
        return row;
    }

    /** porcelain 状态 + numstat 增删行合并（staged 与 unstaged 两段分别解析后按 path 合并）。 */
    static List<Map<String, Object>> collectChanges(Path repo) {
        GitCli.Result status = GitCli.run(repo, GIT_TIMEOUT_SEC, "git", "status", "--porcelain=v1");
        if (status.exitCode() != 0) {
            throw new IllegalStateException("git status 失败: " + status.err().strip());
        }
        Map<String, String> codes = parsePorcelain(status.out());
        Map<String, int[]> numstat = new HashMap<>();
        mergeNumstat(numstat, GitCli.run(repo, GIT_TIMEOUT_SEC, "git", "diff", "--numstat"));
        mergeNumstat(numstat, GitCli.run(repo, GIT_TIMEOUT_SEC, "git", "diff", "--numstat", "--cached"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, String> e : codes.entrySet()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("path", e.getKey());
            c.put("code", e.getValue());
            int[] stat = numstat.get(e.getKey());
            if (stat != null) {
                c.put("adds", stat[0]);
                c.put("dels", stat[1]);
            }
            out.add(c);
        }
        return out;
    }

    /**
     * 解析 {@code git status --porcelain=v1}：path → XY 状态码（原样保留两位，分组语义留给前端）。
     * 重命名行形如 {@code R  old -> new}，取新路径。带引号路径（中文/空格）剥掉首尾引号——
     * porcelain 对特殊字符默认 C 风格引号转义（未配 core.quotePath=false，转义序列原样保留，
     * 仅用于展示与定位，不解析八进制转义）。
     */
    static Map<String, String> parsePorcelain(String out) {
        Map<String, String> codes = new LinkedHashMap<>();
        for (String line : out.split("\\R")) {
            if (line.length() < 4) {
                continue;
            }
            String code = line.substring(0, 2);
            String path = line.substring(3).strip();
            int arrow = path.indexOf(" -> ");
            if (arrow >= 0) {
                path = path.substring(arrow + 4);
            }
            if (path.length() >= 2 && path.startsWith("\"") && path.endsWith("\"")) {
                path = path.substring(1, path.length() - 1);
            }
            if (!path.isEmpty()) {
                codes.put(path, code);
            }
        }
        return codes;
    }

    /** 合并一段 numstat（{@code adds<TAB>dels<TAB>path}；二进制行是 {@code -<TAB>-<TAB>path}，跳过）。 */
    static void mergeNumstat(Map<String, int[]> acc, GitCli.Result r) {
        if (r.exitCode() != 0) {
            return;
        }
        for (String line : r.out().split("\\R")) {
            String[] parts = line.split("\t");
            if (parts.length < 3 || "-".equals(parts[0])) {
                continue;
            }
            try {
                int[] stat = acc.computeIfAbsent(parts[2], k -> new int[2]);
                stat[0] += Integer.parseInt(parts[0]);
                stat[1] += Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {
                // 跳过解析不了的行
            }
        }
    }
}
