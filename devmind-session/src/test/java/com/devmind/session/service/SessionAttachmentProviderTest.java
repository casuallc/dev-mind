package com.devmind.session.service;

import com.devmind.common.agent.exec.ContextAssemblyRequest;
import com.devmind.common.agent.exec.ContextContribution;
import com.devmind.common.agent.exec.ManifestItem;
import com.devmind.common.attachment.AttachmentContentResolver;
import com.devmind.common.exception.DevMindException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SessionAttachmentProvider 单测（无 Spring 上下文）：CAP-68 创建会话即带附件——
 * 正常投送（attachments/&lt;id&gt;-&lt;净化名&gt;）、缺失跳过标注、上限截断、模块未装配抛错、
 * 文件名净化（basename 防穿越 / 中文剥掉兜底扩展名）。
 */
class SessionAttachmentProviderTest {

    private final SessionAttachmentProviderTest.FakeResolver resolver = new FakeResolver();
    private final SessionAttachmentProvider provider = new SessionAttachmentProvider(fixedProvider(resolver));

    @Test
    void emptyWhenNoAttachments() {
        ContextContribution c = provider.contribute(req(List.of()));
        assertTrue(c.inputs().isEmpty());
        assertTrue(c.claudeMdSections().isEmpty());
        ContextContribution nullIds = provider.contribute(req(null));
        assertTrue(nullIds.inputs().isEmpty());
    }

    @Test
    void deliversWithSanitizedNameAndSection() {
        resolver.put("a1", "需求说明书.pdf", "application/pdf", "pdf-bytes");
        resolver.put("a2", "design draft.png", "image/png", "png-bytes");
        ContextContribution c = provider.contribute(req(List.of("a1", "a2", "a1"))); // 重复选择去重
        assertEquals(2, c.inputs().size());
        // 全中文名净化后只剩扩展名 → 补占位名（见 sanitize 测试组）
        assertEquals("attachments/a1-attachment.pdf", c.inputs().get(0).path());
        assertEquals("attachments/a2-designdraft.png", c.inputs().get(1).path());
        assertEquals("pdf-bytes",
                new String(Base64.getDecoder().decode(c.inputs().get(0).base64()), StandardCharsets.UTF_8));
        String section = c.claudeMdSections().get(0);
        assertTrue(section.contains("## 会话附件"), section);
        assertTrue(section.contains(".devmind/input/attachments/a1-"), section);
        assertEquals(2, c.items().size());
        assertTrue(c.items().stream().allMatch(i -> ManifestItem.KIND_ATTACHMENT.equals(i.kind())
                && ManifestItem.SOURCE_REQUEST.equals(i.source())));
    }

    @Test
    void missingAttachmentSkippedWithNote() {
        resolver.put("a1", "ok.txt", "text/plain", "txt");
        ContextContribution c = provider.contribute(req(List.of("gone", "a1")));
        assertEquals(1, c.inputs().size());
        String section = c.claudeMdSections().get(0);
        assertTrue(section.contains("❌"), section);
        assertTrue(section.contains("gone"), section);
    }

    @Test
    void capsFileCountAndNotesOmission() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String id = "id" + i;
            resolver.put(id, "f" + i + ".txt", "text/plain", "x");
            ids.add(id);
        }
        ContextContribution c = provider.contribute(req(ids));
        assertEquals(10, c.inputs().size());
        assertTrue(c.claudeMdSections().get(0).contains("已省略"), c.claudeMdSections().get(0));
    }

    @Test
    void oversizedFileOmittedWithNote() {
        resolver.put("big", "big.bin", "application/octet-stream",
                new byte[(int) (5L * 1024 * 1024 + 1)]);
        ContextContribution c = provider.contribute(req(List.of("big")));
        assertTrue(c.inputs().isEmpty());
        assertTrue(c.claudeMdSections().get(0).contains("超过单文件上限"), c.claudeMdSections().get(0));
    }

    @Test
    void missingResolverModuleFailsVisibly() {
        SessionAttachmentProvider noModule = new SessionAttachmentProvider(fixedProvider(null));
        // 显式选的附件不能静默丢：模块未装配 = 确定性失败（与 FR-05/06 输入链路同口径）
        assertThrows(DevMindException.class, () -> noModule.contribute(req(List.of("a1"))));
    }

    @Test
    void sanitizeKeepsBasenameAndFallsBackForChinese() {
        assertEquals("passwd", SessionAttachmentProvider.safeName(
                new AttachmentContentResolver.ResolvedAttachment("x", "text/plain", new byte[0],
                        "../../etc/passwd")));
        assertEquals("evil.txt", SessionAttachmentProvider.safeName(
                new AttachmentContentResolver.ResolvedAttachment("x", "text/plain", new byte[0],
                        "C:\\temp\\evil.txt")));
        // 全中文名净化后只剩扩展名：补占位名 + mime 兜底
        assertEquals("attachment.pdf", SessionAttachmentProvider.safeName(
                new AttachmentContentResolver.ResolvedAttachment("x", "application/pdf", new byte[0],
                        "设计稿.pdf")));
        assertEquals("attachment.bin", SessionAttachmentProvider.safeName(
                new AttachmentContentResolver.ResolvedAttachment("x", null, new byte[0], null)));
    }

    // ---------------- fakes ----------------

    private ContextAssemblyRequest req(List<String> attachmentIds) {
        return new ContextAssemblyRequest("p1", List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), true, false, null, attachmentIds);
    }

    /** id → 字节表；put 过的 id 才解析得到（模拟附件不存在/已硬删）。 */
    private static class FakeResolver implements AttachmentContentResolver {
        private final java.util.Map<String, ResolvedAttachment> table = new java.util.HashMap<>();

        void put(String id, String name, String contentType, String bytes) {
            table.put(id, new ResolvedAttachment(id, contentType,
                    bytes.getBytes(StandardCharsets.UTF_8), name));
        }

        void put(String id, String name, String contentType, byte[] bytes) {
            table.put(id, new ResolvedAttachment(id, contentType, bytes, name));
        }

        @Override
        public Optional<ResolvedAttachment> resolve(String attachmentId) {
            return resolveAny(attachmentId);
        }

        @Override
        public Optional<ResolvedAttachment> resolveAny(String attachmentId) {
            return Optional.ofNullable(table.get(attachmentId));
        }
    }

    private static <T> ObjectProvider<T> fixedProvider(T bean) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return bean;
            }

            @Override
            public T getIfAvailable() {
                return bean;
            }

            @Override
            public T getIfUnique() {
                return bean;
            }

            @Override
            public T getObject() {
                return bean;
            }
        };
    }
}
