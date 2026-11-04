package com.devmind.security.engine;

import com.devmind.security.parse.SarifParser;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 密钥泄漏引擎适配（CAP-66 FR-02/03/§7）：Gitleaks（SARIF 报告）。
 *
 * <p>{@code gitleaks dir} = 只扫当前检出工作区（--no-git 语义），不扫 git history
 * （历史泄漏排查另立 CAP，见文档开放问题）；泄漏即退出码 1 → {@code || test -f} 放行上传；
 * 零泄漏时老版本可能不落报告 → 补空 SARIF 兜底，避免误报引擎 FAILED。</p>
 *
 * <p><b>脱敏红线</b>：命中行内容落库前打码（只留前后各 4 字符）；codeHash 对脱敏后内容计算
 * （避免密钥原文的可逆穷举面）；原始 SARIF 附件下载限 ADMIN。</p>
 */
@Component
public class SecretsEngineAdapter implements ScanEngineAdapter {

    /** 空结果兜底 SARIF（零泄漏且引擎未落报告时上传它，语义=扫过且无发现） */
    static final String EMPTY_SARIF =
            "{\"version\":\"2.1.0\",\"runs\":[{\"tool\":{\"driver\":{\"name\":\"gitleaks\",\"rules\":[]}},\"results\":[]}]}";

    private final ObjectMapper mapper;

    public SecretsEngineAdapter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String engineId() {
        return "secrets";
    }

    @Override
    public String displayName() {
        return "Gitleaks（密钥泄漏）";
    }

    @Override
    public String binaryName() {
        return "gitleaks";
    }

    @Override
    public String versionCommand() {
        return "gitleaks version";
    }

    @Override
    public String reportFormat() {
        return "sarif";
    }

    @Override
    public String reportFileName() {
        return "gitleaks.sarif";
    }

    @Override
    public String execScript(ScanExecContext ctx) {
        String up = "\"$" + ScanExecContext.ENV_UPLOAD_URL + "\"";
        String tk = "\"$" + ScanExecContext.ENV_UPLOAD_TOKEN + "\"";
        return """
                set -e
                mkdir -p .devmind-scan
                gitleaks dir --report-format sarif --report-path .devmind-scan/gitleaks.sarif . || test -f .devmind-scan/gitleaks.sarif
                if [ ! -f .devmind-scan/gitleaks.sarif ]; then
                  echo '%s' > .devmind-scan/gitleaks.sarif
                fi
                curl -fsS -H "X-Scan-Token: %s" -F "engine=secrets" -F "format=sarif" -F "file=@.devmind-scan/gitleaks.sarif" %s
                """.formatted(EMPTY_SARIF, tk, up);
    }

    @Override
    public List<NormalizedFinding> parse(byte[] report) {
        List<SarifParser.SarifResult> results = SarifParser.parse(report, mapper);
        List<NormalizedFinding> out = new ArrayList<>();
        for (SarifParser.SarifResult r : results) {
            Map<String, Object> detail = new LinkedHashMap<>(r.extra());
            detail.put("message", r.message());
            detail.put("level", r.level());
            String masked = mask(r.snippet());
            out.add(new NormalizedFinding(engineId(), r.ruleId(),
                    SarifParser.mapLevel(r.level()),
                    "疑似硬编码密钥（" + r.ruleId() + "）",
                    r.uri(), r.startLine(), masked,
                    null, null, null, null, null, null, detail));
        }
        return out;
    }

    @Override
    public String fingerprint(NormalizedFinding f) {
        // lineContent 已是脱敏形态，哈希不暴露密钥原文的可逆穷举面
        return ScanEngineAdapter.sha1("secrets", nullToEmpty(f.ruleId()),
                nullToEmpty(f.filePath()), SastEngineAdapter.lineHash(f.lineContent()));
    }

    /** 命中内容脱敏：只留前后各 4 字符，中间打码；短串全码 */
    static String mask(String s) {
        if (s == null) {
            return null;
        }
        if (s.length() <= 8) {
            return "********";
        }
        return s.substring(0, 4) + "****" + s.substring(s.length() - 4);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
