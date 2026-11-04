package com.devmind.security.engine;

import com.devmind.security.parse.DependencyCheckJsonParser;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * SCA 引擎适配（CAP-66 FR-02/03）：OWASP dependency-check。
 *
 * <p>脚本：引擎调用（--out 自动建目录，无需 mkdir）→ JSON/HTML 报告上传 →
 * 附带采集 Maven/Gradle 依赖树（FR-11-1 引入链，尽力而为不上传失败不影响主报告）。
 * 白名单依赖：dependency-check / curl / mvn（或 ./gradlew/gradle）须进 runner execAllowlist。</p>
 *
 * <p>fingerprint = sha1("sca" | cve | groupId:artifactId)——jar 物理路径随版本漂移不作输入；
 * 坐标缺失（purl 与文件名都剥不出）回落 fileName，同版本内稳定。</p>
 */
@Component
public class ScaEngineAdapter implements ScanEngineAdapter {

    private final ObjectMapper mapper;

    public ScaEngineAdapter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String engineId() {
        return "sca";
    }

    @Override
    public String displayName() {
        return "Dependency-Check（SCA 依赖漏洞）";
    }

    @Override
    public String binaryName() {
        return "dependency-check";
    }

    @Override
    public String versionCommand() {
        return "dependency-check --version";
    }

    @Override
    public String reportFormat() {
        return "json";
    }

    @Override
    public String reportFileName() {
        return "dependency-check-report.json";
    }

    @Override
    public String execScript(ScanExecContext ctx) {
        String up = "\"$" + ScanExecContext.ENV_UPLOAD_URL + "\"";
        String tk = "\"$" + ScanExecContext.ENV_UPLOAD_TOKEN + "\"";
        return """
                set -e
                dependency-check --project scan --scan . --format JSON --format HTML --out .devmind-scan
                curl -fsS -H "X-Scan-Token: %s" -F "engine=sca" -F "format=json" -F "file=@.devmind-scan/dependency-check-report.json" %s
                curl -fsS -H "X-Scan-Token: %s" -F "engine=sca" -F "format=html" -F "file=@.devmind-scan/dependency-check-report.html" %s
                if [ -f pom.xml ]; then
                  mvn -B -q dependency:tree -DoutputFile=.devmind-scan/dep-tree.txt > /dev/null 2>&1 || true
                fi
                if [ -f build.gradle ]; then
                  gradle -q dependencies --configuration runtimeClasspath > .devmind-scan/dep-tree.txt 2> /dev/null || true
                fi
                if [ -f .devmind-scan/dep-tree.txt ]; then
                  curl -fsS -H "X-Scan-Token: %s" -F "engine=sca" -F "format=deptree" -F "file=@.devmind-scan/dep-tree.txt" %s || true
                fi
                """.formatted(tk, up, tk, up, tk, up);
    }

    @Override
    public List<NormalizedFinding> parse(byte[] report) {
        return DependencyCheckJsonParser.parse(report, mapper);
    }

    @Override
    public String fingerprint(NormalizedFinding f) {
        String coord = f.groupId() != null
                ? f.groupId() + ":" + f.artifactId()
                : String.valueOf(f.detail().getOrDefault("fileName", f.artifactId()));
        return ScanEngineAdapter.sha1("sca", nullToEmpty(f.cve()), coord);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
