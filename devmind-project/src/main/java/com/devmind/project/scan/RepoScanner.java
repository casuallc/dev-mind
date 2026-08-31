package com.devmind.project.scan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CAP-02 FR-07 项目上下文扫描：git ls-files + 目录树 + 技术栈识别 + 既有 API 提取，
 * 产出可注入需求对话/方案的 Markdown 摘要。全部走 git CLI（Windows 兼容），不依赖 Agent，可人工修正。
 */
@Component
public class RepoScanner {

    private static final Logger log = LoggerFactory.getLogger(RepoScanner.class);

    private static final Pattern ANNOTATION = Pattern.compile(
            "@(Get|Post|Put|Delete|Request)Mapping\\((.*?)\\)", Pattern.DOTALL);
    private static final Pattern STRING_LIT = Pattern.compile("\"([^\"]*)\"");

    /** 扫描仓库生成 Markdown 上下文摘要。仓库无效时返回空串（不抛，调用方自行降级）。 */
    public String scan(Path repo) {
        try {
            return doScan(repo);
        } catch (Exception e) {
            log.warn("项目上下文扫描失败: {} err={}", repo, e.getMessage());
            return "";
        }
    }

    private String doScan(Path repo) throws IOException {
        Path root = repo.toAbsolutePath().normalize();
        Result files = git(root, "ls-files");
        List<String> fileList = files.out().lines()
                .map(String::trim).filter(l -> !l.isBlank()).toList();

        StringBuilder sb = new StringBuilder();
        sb.append("# 项目上下文摘要\n\n");
        sb.append("> 自动生成于 ").append(java.time.Instant.now()).append("；可人工修正。\n\n");

        sb.append("## 技术栈\n");
        List<String> stack = detectStack(root, fileList);
        for (String s : stack) {
            sb.append("- ").append(s).append('\n');
        }
        if (stack.isEmpty()) {
            sb.append("- （未识别出明显技术栈）\n");
        }
        sb.append('\n');

        sb.append("## 目录结构\n");
        appendTree(sb, fileList);

        sb.append("## 关键模块\n");
        List<String> modules = detectModules(root, fileList);
        if (modules.isEmpty()) {
            sb.append("- （未识别出子模块）\n");
        } else {
            for (String m : modules) {
                sb.append("- ").append(m).append('\n');
            }
        }
        sb.append('\n');

        sb.append("## 既有 API\n");
        List<String> apis = extractApis(root);
        if (apis.isEmpty()) {
            sb.append("- （未在 src/main/java 发现 REST 注解）\n");
        } else {
            for (String a : apis) {
                sb.append("- ").append(a).append('\n');
            }
        }
        sb.append('\n');

        sb.append("## 统计\n");
        sb.append("- 跟踪文件数：").append(fileList.size()).append('\n');
        long java = fileList.stream().filter(f -> f.endsWith(".java")).count();
        long js = fileList.stream().filter(f -> f.endsWith(".ts") || f.endsWith(".tsx")).count();
        if (java > 0) sb.append("- Java 文件：").append(java).append('\n');
        if (js > 0) sb.append("- TS/TSX 文件：").append(js).append('\n');
        return sb.toString();
    }

    // ---------------- 技术栈 ----------------

    private List<String> detectStack(Path root, List<String> files) {
        Set<String> stack = new LinkedHashSet<>();
        if (hasFile(files, "pom.xml")) {
            stack.add("Java + Maven");
            if (readMaybe(root, "pom.xml").orElse("").contains("spring-boot")) {
                stack.add("Spring Boot");
            }
        }
        if (hasFile(files, "build.gradle") || hasFile(files, "build.gradle.kts")) {
            stack.add("Java/Kotlin + Gradle");
        }
        if (hasFile(files, "package.json")) {
            stack.add("Node.js");
            String pkg = readMaybe(root, "package.json").orElse("");
            if (pkg.contains("\"react\"") && pkg.contains("\"vite\"")) {
                stack.add("React + Vite");
            } else if (pkg.contains("\"vite\"")) {
                stack.add("Vite");
            }
        }
        if (hasFile(files, "go.mod")) stack.add("Go");
        if (hasAny(files, f -> f.equals("requirements.txt") || f.equals("pyproject.toml"))) {
            stack.add("Python");
        }
        if (hasAny(files, f -> f.endsWith(".sln") || f.endsWith(".csproj"))) {
            stack.add(".NET");
        }
        if (hasFile(files, "Dockerfile") || hasFile(files, "docker-compose.yml")) {
            stack.add("Docker");
        }
        if (hasFile(files, "pom.xml")) {
            // 多模块 Maven：以子模块 pom 推断
            long modulePoms = files.stream().filter(f -> f.endsWith("/pom.xml")).count();
            if (modulePoms > 0) stack.add("Maven 多模块（" + modulePoms + " 个子模块）");
        }
        return new ArrayList<>(stack);
    }

    // ---------------- 目录树 ----------------

    private void appendTree(StringBuilder sb, List<String> files) {
        TreeSet<String> top = new TreeSet<>();
        Map<String, Set<String>> second = new LinkedHashMap<>();
        for (String f : files) {
            int s1 = f.indexOf('/');
            if (s1 < 0) {
                top.add(f);
                continue;
            }
            String first = f.substring(0, s1);
            top.add(first + "/");
            int s2 = f.indexOf('/', s1 + 1);
            if (s2 > 0) {
                second.computeIfAbsent(first, k -> new TreeSet<>())
                        .add(f.substring(s1 + 1, s2) + "/");
            }
        }
        int shown = 0;
        for (String t : top) {
            sb.append("- ").append(t).append('\n');
            if (t.endsWith("/")) {
                Set<String> kids = second.get(t.substring(0, t.length() - 1));
                if (kids != null) {
                    for (String k : kids) {
                        sb.append("  - ").append(k).append('\n');
                    }
                }
            }
            if (++shown >= 80) {
                sb.append("- …（目录较多，已截断）\n");
                break;
            }
        }
        sb.append('\n');
    }

    // ---------------- 关键模块 ----------------

    private List<String> detectModules(Path root, List<String> files) {
        // 顶层目录中带构建清单（pom/build.gradle/package.json）视为模块
        List<String> modules = new ArrayList<>();
        Map<String, String> manifest = new LinkedHashMap<>();
        for (String f : files) {
            int s = f.indexOf('/');
            if (s < 0) continue;
            String first = f.substring(0, s);
            String leaf = f.substring(s + 1);
            if (!manifest.containsKey(first) && (leaf.equals("pom.xml") || leaf.equals("build.gradle")
                    || leaf.equals("build.gradle.kts") || leaf.equals("package.json"))) {
                manifest.put(first, leaf);
            }
        }
        manifest.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> modules.add("`" + e.getKey() + "/`（" + e.getValue() + "）"));
        return modules;
    }

    // ---------------- 既有 API ----------------

    private List<String> extractApis(Path root) {
        Result grep = git(root, "grep", "-n", "--no-color",
                "-E", "@(Get|Post|Put|Delete|Request)Mapping", "--", "*.java");
        List<String> lines = grep.out().lines().toList();
        if (lines.isEmpty()) {
            return List.of();
        }
        List<String> apis = new ArrayList<>();
        for (String line : lines) {
            int colon = line.indexOf(':', line.indexOf(':') + 1);
            if (colon < 0) continue;
            String file = line.substring(0, line.indexOf(':'));
            String annotation = line.substring(colon + 1);
            Matcher m = ANNOTATION.matcher(annotation);
            if (m.find()) {
                Matcher lit = STRING_LIT.matcher(m.group(2));
                String path = lit.find() ? lit.group(1) : "";
                String method = m.group(1).toUpperCase();
                if ("REQUEST".equals(method)) method = "MAP";
                apis.add(method + " " + path + "  ← `" + file + "`");
            }
        }
        apis.sort(Comparator.naturalOrder());
        return apis.size() > 120 ? apis.subList(0, 120) : apis;
    }

    // ---------------- 工具 ----------------

    private boolean hasFile(List<String> files, String name) {
        return files.contains(name);
    }

    private boolean hasAny(List<String> files, java.util.function.Predicate<String> p) {
        return files.stream().anyMatch(p);
    }

    private java.util.Optional<String> readMaybe(Path root, String relative) {
        Path p = root.resolve(relative);
        if (!Files.isRegularFile(p)) {
            return java.util.Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(p);
            return java.util.Optional.of(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
    }

    private record Result(int exit, String out) {}

    private Result git(Path cwd, String... args) {
        String[] cmd = new String[args.length + 3];
        cmd[0] = "git";
        cmd[1] = "-C";
        cmd[2] = cwd.toString();
        System.arraycopy(args, 0, cmd, 3, args.length);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Result(-1, out);
            }
            return new Result(p.exitValue(), out);
        } catch (IOException e) {
            return new Result(-1, "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "");
        }
    }
}
