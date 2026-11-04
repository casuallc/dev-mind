package com.devmind.security.engine;

import com.devmind.security.config.SecurityProperties;
import com.devmind.security.parse.SarifParser;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SAST 引擎适配（CAP-66 FR-02/03）：Semgrep（--sarif）。
 *
 * <p>semgrep 发现即退出码 1 → 脚本以 {@code || test -f <report>} 放行到上传步；
 * 报告缺失（引擎真失败）时上传步 curl 失败使步骤失败 → 引擎 FAILED。
 * 规则集取配置 {@code devmind.security.semgrep-config}（默认 auto 需外网；离线配本地规则路径）。
 * 白名单依赖：semgrep / curl / mkdir。</p>
 */
@Component
public class SastEngineAdapter implements ScanEngineAdapter {

    private final ObjectMapper mapper;
    private final SecurityProperties props;

    public SastEngineAdapter(ObjectMapper mapper, SecurityProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    @Override
    public String engineId() {
        return "sast";
    }

    @Override
    public String displayName() {
        return "Semgrep（SAST 代码缺陷）";
    }

    @Override
    public String binaryName() {
        return "semgrep";
    }

    @Override
    public String versionCommand() {
        return "semgrep --version";
    }

    @Override
    public String reportFormat() {
        return "sarif";
    }

    @Override
    public String reportFileName() {
        return "semgrep.sarif";
    }

    @Override
    public String execScript(ScanExecContext ctx) {
        String up = "\"$" + ScanExecContext.ENV_UPLOAD_URL + "\"";
        String tk = "\"$" + ScanExecContext.ENV_UPLOAD_TOKEN + "\"";
        String config = props.getSemgrepConfig();
        return """
                set -e
                mkdir -p .devmind-scan
                semgrep scan --config %s --sarif --output .devmind-scan/semgrep.sarif --metrics off . || test -f .devmind-scan/semgrep.sarif
                curl -fsS -H "X-Scan-Token: %s" -F "engine=sast" -F "format=sarif" -F "file=@.devmind-scan/semgrep.sarif" %s
                """.formatted(config, tk, up);
    }

    @Override
    public List<NormalizedFinding> parse(byte[] report) {
        List<SarifParser.SarifResult> results = SarifParser.parse(report, mapper);
        List<NormalizedFinding> out = new ArrayList<>();
        for (SarifParser.SarifResult r : results) {
            Map<String, Object> detail = new LinkedHashMap<>(r.extra());
            detail.put("message", r.message());
            detail.put("level", r.level());
            String title = r.message() != null && !r.message().isBlank()
                    ? truncate(r.message(), 120) : r.ruleId();
            out.add(new NormalizedFinding(engineId(), r.ruleId(),
                    SarifParser.mapLevel(r.level()), title,
                    r.uri(), r.startLine(), r.snippet(),
                    null, null, null, null, null, null, detail));
        }
        return out;
    }

    @Override
    public String fingerprint(NormalizedFinding f) {
        return ScanEngineAdapter.sha1("sast", nullToEmpty(f.ruleId()),
                nullToEmpty(f.filePath()), lineHash(f.lineContent()));
    }

    /** 目标行去空白哈希（±1 行漂移容忍：lineNo 不进 fingerprint，内容不变即同指纹） */
    static String lineHash(String line) {
        if (line == null) {
            return "";
        }
        return ScanEngineAdapter.sha1(line.replaceAll("\\s+", ""));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
