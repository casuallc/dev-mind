package com.devmind.decisionlab.lab;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.decisionlab.config.DecisionLabProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * CAP-56 脚本目录（{@code devmind.decision-lab.scripts-dir}）的读取：打包执行包时把目录里的
 * {@code *.py} 一并塞进去，节点拉包后直接跑。
 *
 * <p><b>脚本从配置目录读，不从 classpath 读</b>：这些脚本要能改（改指标口径、换奖励函数）
 * 而不重新构建 jar，也要能在节点上人工核对"跑的是哪一版"。放进 jar 就只能改代码再发版，
 * 而这个仓库的评测口径还在快速试错期。</p>
 *
 * <p>脚本可以互相 import（{@code _rl_common.py} 之类），所以打包时收整个目录的
 * {@code .py}，不是只收入口那一个——少收一个共用模块，节点上就是 ImportError，
 * 而它看起来像"脚本写错了"。入口只决定清单里的 {@code entry}，其余是包内容。</p>
 *
 * <p>目录/入口缺失在<b>触发阶段</b>就拒绝（{@link #requireRunnable}）：等到节点上拉包 404、
 * 或跑起来才发现没有脚本，人去查的会是节点而不是这项配置。</p>
 */
@Component
public class LabScripts {

    private static final Logger log = LoggerFactory.getLogger(LabScripts.class);

    /** 评测入口脚本（包内文件名，同时是 runner 直接跑的那个） */
    public static final String EVAL_ENTRY = "laya_eval.py";

    /** 微调入口脚本 */
    public static final String FINETUNE_ENTRY = "laya_train.py";

    private final DecisionLabProperties props;

    public LabScripts(DecisionLabProperties props) {
        this.props = props;
    }

    /** 解析后的脚本目录绝对路径（未配置返回 empty） */
    public Optional<Path> dir() {
        String raw = props.getScriptsDir();
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Path.of(raw.strip()).toAbsolutePath().normalize());
    }

    Map<String, byte[]> readAll() {
        Path dir = dir().orElseThrow(() -> new DevMindException(ErrorCode.BAD_REQUEST,
                "未配置评测/微调脚本目录（devmind.decision-lab.scripts-dir），无法打包执行包"));
        if (!Files.isDirectory(dir)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "评测/微调脚本目录不存在: " + dir + "（配置项 devmind.decision-lab.scripts-dir）");
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (Stream<Path> list = Files.list(dir)) {
            List<Path> scripts = list
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".py"))
                    .sorted()
                    .toList();
            for (Path p : scripts) {
                files.put(p.getFileName().toString(), Files.readAllBytes(p));
            }
        } catch (IOException e) {
            throw new DevMindException(ErrorCode.INTERNAL, "读取脚本目录失败: " + dir + " — " + e.getMessage());
        }
        if (files.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "脚本目录内没有任何 .py 文件: " + dir + "（配置项 devmind.decision-lab.scripts-dir）");
        }
        return files;
    }

    /**
     * 打包用的脚本集合 + 入口校验。
     *
     * @param entry 入口脚本名（{@link #EVAL_ENTRY} / {@link #FINETUNE_ENTRY}）
     */
    public Map<String, byte[]> readForEntry(String entry) {
        Map<String, byte[]> files = readAll();
        if (!files.containsKey(entry)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "脚本目录里没有入口脚本 " + entry + ": " + dir().orElse(null)
                            + "（实际有: " + String.join(", ", files.keySet()) + "）");
        }
        return files;
    }

    /**
     * 触发前自检：目录、入口、python 路径都要能跑起来，缺一样就带可操作提示拒绝。
     *
     * @param pythonPath 本次用的解释器路径（触发时可覆盖配置值）
     */
    public void requireRunnable(String entry, String pythonPath) {
        requirePython(pythonPath);
        Map<String, byte[]> files = readForEntry(entry);
        log.debug("脚本自检通过: entry={} 文件={} 目录={}", entry, files.keySet(), dir().orElse(null));
    }

    /**
     * python 路径自检：<b>必须是无空白的单 token</b>。
     *
     * <p>runner 的 execAllowlist 逐行取首个 token 做前缀匹配，带空格的路径首个 token 就断在
     * 空格处（{@code "C:/Program} 之类），永远匹配不上白名单，报出来的是"命令不在白名单"
     * 这种与真实原因隔了一层的信息。所以在服务端就说清楚：换一个不含空格的解释器路径
     * （venv 的 {@code bin/python} / {@code Scripts/python.exe} 全路径都不含空格）。</p>
     */
    public void requirePython(String pythonPath) {
        String p = pythonPath == null ? "" : pythonPath.strip();
        if (p.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "pythonPath 为空（配置项 devmind.decision-lab.python-path）");
        }
        if (p.chars().anyMatch(Character::isWhitespace)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "pythonPath 不能含空白: " + p + "（runner 按首个 token 校验 execAllowlist，"
                            + "含空格的路径会被节点白名单打回；请用不含空格的解释器路径，"
                            + "如 venv 的 bin/python 或 Scripts/python.exe 全路径）");
        }
        if (p.contains("\"") || p.contains("'")) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "pythonPath 不能含引号: " + p);
        }
    }

    /** 供诊断：目录路径 + python 路径（触发被拒时把这一行带进错误提示，省一轮翻配置） */
    public String describe() {
        return "scriptsDir=" + dir().map(Path::toString).orElse("(未配置)")
                + " pythonPath=" + (props.getPythonPath() == null ? "" : props.getPythonPath().strip());
    }
}
