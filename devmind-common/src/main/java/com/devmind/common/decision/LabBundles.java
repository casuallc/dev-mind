package com.devmind.common.decision;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * CAP-56 执行包的打包与解包（服务端打包、runner 解包，两边共用同一份格式定义）。
 *
 * <p>格式刻意简单到能用肉眼核对：一个 zip，里面是脚本文件（{@code .py}）+ 数据文件
 * （{@code payload.json}）+ 清单 {@code bundle.json}（{@code {"entry":"…","payloadName":"…"}}）。
 * 不做自定义二进制头、不做嵌套压缩——这个包的全部消费者是节点上一次性的 python 进程，
 * 出问题时运维要能直接 {@code unzip -l} 看明白里面有什么。</p>
 *
 * <p><b>不做 sha256 校验</b>（与上下文包不同）：上下文包是"服务端装配好、帧里带清单"的两段式，
 * 清单里的哈希能在装配时就定下来；执行包是<b>拉取时按需构建</b>的（数据集与任务行在库里，
 * 没人提前持有字节），硬要在帧里带哈希就得先构建一次、再构建一次喂 HTTP，两遍之间还可能不一致。
 * 完整性由传输层（HTTP + zip 自身的 CRC32）兜住，真正要防的"拉错包"由 {@code kind+id} 的路径
 * 与节点 token 认证兜住。</p>
 */
public final class LabBundles {

    /** 清单文件名（包内固定，服务端写、runner 读） */
    public static final String MANIFEST_NAME = "bundle.json";

    /** 数据文件名（服务端与 runner 的默认约定；清单里会再写一遍，以清单为准） */
    public static final String DEFAULT_PAYLOAD_NAME = "payload.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LabBundles() {
    }

    /**
     * 包内清单。
     *
     * @param entry       入口脚本（相对包根的文件名，如 {@code laya_eval.py}；runner 直接跑它）
     * @param payloadName 数据文件名（runner 把它注入 {@code DEVMIND_LAB_PAYLOAD}）
     */
    public record Manifest(String entry, String payloadName) {
    }

    /** 打包：清单 + 脚本文件 + 数据。文件名为包内相对路径（不带目录——runner 解到平铺目录里）。 */
    public static byte[] pack(Manifest manifest, Map<String, byte[]> files, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(MANIFEST_NAME));
            zip.write(manifestJson(manifest));
            zip.closeEntry();
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
            String payloadName = manifest.payloadName() == null || manifest.payloadName().isBlank()
                    ? DEFAULT_PAYLOAD_NAME : manifest.payloadName();
            zip.putNextEntry(new ZipEntry(payloadName));
            zip.write(payload == null ? new byte[0] : payload);
            zip.closeEntry();
        } catch (IOException e) {
            // 内存流上的写入不会因磁盘而失败；真出错说明上面的代码写错了，别吞
            throw new IllegalStateException("打包执行包失败: " + e.getMessage(), e);
        }
        return out.toByteArray();
    }

    /** 读清单；包损坏/缺清单/清单不是 JSON 一律抛 IOException（调用方按"拉包失败"收口，不降级）。 */
    public static Manifest readManifest(byte[] zip) throws IOException {
        JsonNode node;
        try {
            node = MAPPER.readTree(readEntry(zip, MANIFEST_NAME));
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            // Jackson 3 的解析异常是非受检的；这一层的契约是"读不出来 = IOException"，
            // 不转的话调用方要同时 catch 两族异常才能不漏
            throw new IOException("执行包清单不是合法 JSON: " + e.getMessage(), e);
        }
        String entry = node.path("entry").asText("");
        String payload = node.path("payloadName").asText("");
        if (entry.isBlank()) {
            throw new IOException("执行包清单缺 entry（入口脚本）");
        }
        return new Manifest(entry,
                payload.isBlank() ? DEFAULT_PAYLOAD_NAME : payload);
    }

    /**
     * 解包到目录（平铺，不保留目录层级：包内文件名禁止带路径，防 zip-slip 借 {@code ../} 写到目录外）。
     *
     * @return 解出来的文件名集合（相对路径即文件名）
     */
    public static Map<String, Path> extract(byte[] zip, Path dir) throws IOException {
        Map<String, Path> written = new LinkedHashMap<>();
        Files.createDirectories(dir);
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = safeName(entry.getName());
                Path target = dir.resolve(name);
                Files.write(target, in.readAllBytes());
                written.put(name, target);
                in.closeEntry();
            }
        }
        return written;
    }

    /** 取包内单个条目内容 */
    public static byte[] readEntry(byte[] zip, String name) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (!entry.isDirectory() && name.equals(entry.getName())) {
                    return in.readAllBytes();
                }
            }
        }
        throw new IOException("执行包里没有 " + name);
    }

    private static byte[] manifestJson(Manifest manifest) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("entry", manifest.entry());
        node.put("payloadName", manifest.payloadName() == null || manifest.payloadName().isBlank()
                ? DEFAULT_PAYLOAD_NAME : manifest.payloadName());
        return MAPPER.writeValueAsBytes(node);
    }

    /** 包内文件名安全化：只取文件名部分，拒空名。zip-slip 防护在解包处也再判一次。 */
    private static String safeName(String raw) throws IOException {
        String name = raw == null ? "" : raw.replace('\\', '/');
        // 取最后一段：即使包里写了 a/../b 也只落成 b，绝不写到目标目录之外
        int slash = name.lastIndexOf('/');
        name = slash < 0 ? name : name.substring(slash + 1);
        if (name.isBlank() || ".".equals(name) || "..".equals(name)) {
            throw new IOException("执行包内含非法文件名: " + raw);
        }
        return name;
    }
}
