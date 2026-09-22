package com.devmind.agent.runner;

import com.devmind.common.decision.LabBundles;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CAP-56 执行包拉取（通道照抄 {@link ContextPuller}：节点 token 走 {@code ?token=}，
 * 基址取 runner 自己的 serverUrl）：exec 帧带 {@code bundle{kind,id}} 时，凭节点 token
 * 拉 zip → 解到临时目录 → 返回入口脚本与数据文件的绝对路径，调用方注入
 * {@code DEVMIND_LAB_SCRIPT} / {@code DEVMIND_LAB_PAYLOAD} 再跑命令。
 *
 * <p><b>为什么拉包而不把数据塞进 exec 帧</b>：帧是 WS 上的 JSON，评测集几百 KB～几 MB
 * 会让每帧编解码变重；且"脚本文件"在帧里本就表达不了（帧只能承载命令串）。</p>
 *
 * <p><b>不降级</b>（同 CAP-34 上下文包口径）：拉不到/解不开/清单缺入口 = 抛异常，
 * 调用方返回非零退出码。若降级为"没有脚本地跑一遍命令"，失败现场会指向脚本自身，
 * 而真实原因在网络或任务状态——这类错位最难查。</p>
 *
 * <p><b>代理</b>：本类的 HTTP 拉取<b>不走</b>节点代理。CAP-43 的 {@code NodeProxy} 只在
 * exec/launch 下发时把代理地址注入<b>子进程环境变量</b>（{@code HTTP_PROXY} 等），
 * JVM 自身的 HttpClient 读的是系统属性，从未被接线；服务端通常就在内网、本就不该走外网代理。
 * 需要联网的是脚本进程（pip/HF），它拿到的正是注入过代理的环境。</p>
 */
public final class LabBundlePuller {

    private static final Logger log = LoggerFactory.getLogger(LabBundlePuller.class);

    /** 拉包超时：数据集可能上 MB，比上下文包（60s）放宽一档 */
    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    private LabBundlePuller() {
    }

    /**
     * 物化后的执行包。
     *
     * @param dir      解包目录（调用方用完须 {@link #deleteQuietly}）
     * @param script   入口脚本绝对路径（注入 {@code DEVMIND_LAB_SCRIPT}）
     * @param payload  数据文件绝对路径（注入 {@code DEVMIND_LAB_PAYLOAD}）
     * @param fileName 服务端给的包名（仅日志用）
     */
    public record Materialized(Path dir, Path script, Path payload, String fileName) {
    }

    /**
     * 拉取并解包。
     *
     * @throws IOException 网络失败、包损坏、清单缺入口、解包后文件缺失
     */
    public static Materialized pull(RunnerConfig config, String kind, String id) throws IOException {
        String url = RunnerUpgrader.serverHttpBase(config)
                + "/api/agent/decision-lab/bundles/" + URLEncoder.encode(kind, StandardCharsets.UTF_8)
                + "/" + URLEncoder.encode(id, StandardCharsets.UTF_8)
                + "?token=" + URLEncoder.encode(config.token(), StandardCharsets.UTF_8);
        byte[] body;
        String fileName = kind + "-" + id + ".zip";
        try {
            HttpResponse<byte[]> resp = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                throw new IOException("拉取执行包失败: HTTP " + resp.statusCode()
                        + "（" + kind + "/" + id + "）");
            }
            body = resp.body();
            String disposition = resp.headers().firstValue("Content-Disposition").orElse(null);
            String parsed = fileNameFrom(disposition);
            if (parsed != null) {
                fileName = parsed;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("拉取执行包被中断", e);
        }
        if (body.length == 0) {
            throw new IOException("执行包为空: " + kind + "/" + id);
        }

        // 先读清单再解包：清单说入口是谁，落到哪个文件由解包结果说了算，两边对不上即失败
        LabBundles.Manifest manifest = LabBundles.readManifest(body);
        Path dir = Files.createTempDirectory("devmind-lab-");
        LabBundles.extract(body, dir);
        Path script = requireFile(dir, manifest.entry(), "入口脚本");
        Path payload = requireFile(dir, manifest.payloadName(), "数据文件");
        log.info("执行包物化完成: {} kind={} id={} dir={} entry={}",
                fileName, kind, id, dir, manifest.entry());
        return new Materialized(dir, script, payload, fileName);
    }

    /** 解包结果里必须真有清单指名的文件（清单与实际不一致时立刻失败，别让脚本跑到一半才说找不到） */
    private static Path requireFile(Path dir, String name, String what) throws IOException {
        Path p = dir.resolve(name).normalize();
        if (!p.startsWith(dir) || !Files.isRegularFile(p)) {
            throw new IOException("执行包内缺" + what + ": " + name);
        }
        return p.toAbsolutePath();
    }

    /** 从 {@code Content-Disposition: attachment; filename="x.zip"} 取包名（缺失/畸形返回 null） */
    private static String fileNameFrom(String disposition) {
        if (disposition == null) {
            return null;
        }
        int i = disposition.toLowerCase().indexOf("filename=");
        if (i < 0) {
            return null;
        }
        String name = disposition.substring(i + "filename=".length()).trim();
        if (name.startsWith("\"") && name.endsWith("\"") && name.length() > 1) {
            name = name.substring(1, name.length() - 1);
        }
        return name.isBlank() ? null : name;
    }

    /** 删除解包目录（尽力而为：残留的临时目录不该把 exec 的结果搞成失败，但打日志说明残留） */
    public static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (Exception e) {
            log.warn("执行包临时目录清理失败（可手工删除）: {} {}", dir, e.getMessage());
        }
    }
}
