package com.devmind.session.service;

import com.devmind.common.agent.SessionOutputSink.OutputFile;
import com.devmind.common.attachment.AttachmentStore;
import com.devmind.session.model.SessionEntity;
import com.devmind.session.model.SessionOutputEntity;
import com.devmind.session.repo.SessionOutputRepository;
import com.devmind.session.repo.SessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SessionOutputService 存取单测：store 新增/同名覆盖（幂等）、findContent 按会话+文件名读取；
 * CAP-83 storeBinary 走附件引用行 + 同名互覆删旧附件。
 * 无 Mockito（模块测试惯例），内存 fake 走 JDK 动态代理。
 */
class SessionOutputServiceTest {

    /** sessionId:fileName → 行的内存表；save 时按唯一键 upsert。 */
    private static SessionOutputRepository fakeRepo(Map<String, SessionOutputEntity> table) {
        InvocationHandler h = (p, m, args) -> switch (m.getName()) {
            case "findBySessionIdAndFileName" -> Optional.ofNullable(table.get(args[0] + ":" + args[1]));
            case "save" -> {
                SessionOutputEntity e = (SessionOutputEntity) args[0];
                table.put(e.getSessionId() + ":" + e.getFileName(), e);
                yield e;
            }
            default -> throw new UnsupportedOperationException(m.getName());
        };
        return (SessionOutputRepository) Proxy.newProxyInstance(
                SessionOutputRepository.class.getClassLoader(),
                new Class<?>[]{SessionOutputRepository.class}, h);
    }

    /** 文本路径测试无需会话/附件上下文。 */
    private static SessionOutputService textOnly(SessionOutputRepository repo) {
        return new SessionOutputService(repo, null, null);
    }

    @Test
    void store新增后可按文件名读取() {
        Map<String, SessionOutputEntity> table = new LinkedHashMap<>();
        SessionOutputService svc = textOnly(fakeRepo(table));
        svc.store("s1", List.of(new OutputFile("analysis.md", "# 分析"), new OutputFile("wi-plan.json", "[]")));
        assertTrue(svc.findContent("s1", "analysis.md").isPresent());
        assertEquals("# 分析", svc.findContent("s1", "analysis.md").orElseThrow());
        assertEquals("[]", svc.findContent("s1", "wi-plan.json").orElseThrow());
        assertTrue(svc.findContent("s1", "design.md").isEmpty());
        assertTrue(svc.findContent("s2", "analysis.md").isEmpty());
    }

    @Test
    void 同会话同名文件覆盖旧值() {
        Map<String, SessionOutputEntity> table = new LinkedHashMap<>();
        SessionOutputService svc = textOnly(fakeRepo(table));
        svc.store("s1", List.of(new OutputFile("analysis.md", "v1")));
        svc.store("s1", List.of(new OutputFile("analysis.md", "v2")));
        assertEquals("v2", svc.findContent("s1", "analysis.md").orElseThrow());
        assertEquals(1, table.size(), "同 sessionId+fileName 只留最新一份");
    }

    /** CAP-83 内存附件仓：store 记账返回 id，delete 记录删除。 */
    private static final class FakeAttachments implements AttachmentStore {
        final Map<String, byte[]> stored = new LinkedHashMap<>();
        final List<String> deleted = new ArrayList<>();
        String lastScope;
        String lastOwner;

        @Override
        public String store(InputStream in, long size, String originalName, String contentType,
                            String scope, String uploadedBy, String tags, String description) {
            try {
                String id = "att-" + (stored.size() + 1);
                stored.put(id, in.readAllBytes());
                lastScope = scope;
                lastOwner = uploadedBy;
                return id;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void delete(String attachmentId) {
            deleted.add(attachmentId);
            stored.remove(attachmentId);
        }
    }

    private static SessionRepository fakeSessionRepo(String createdBy) {
        SessionEntity s = new SessionEntity();
        s.setCreatedBy(createdBy);
        InvocationHandler h = (p, m, args) -> switch (m.getName()) {
            case "findById" -> Optional.of(s);
            default -> throw new UnsupportedOperationException(m.getName());
        };
        return (SessionRepository) Proxy.newProxyInstance(
                SessionRepository.class.getClassLoader(),
                new Class<?>[]{SessionRepository.class}, h);
    }

    private static ObjectProvider<AttachmentStore> providerOf(AttachmentStore store) {
        InvocationHandler h = (p, m, args) -> switch (m.getName()) {
            case "getIfAvailable" -> store;
            default -> throw new UnsupportedOperationException(m.getName());
        };
        @SuppressWarnings("unchecked")
        ObjectProvider<AttachmentStore> provider = (ObjectProvider<AttachmentStore>) Proxy.newProxyInstance(
                ObjectProvider.class.getClassLoader(),
                new Class<?>[]{ObjectProvider.class}, h);
        return provider;
    }

    @Test
    void 二进制产出存附件引用行且按会话创建者归属() {
        Map<String, SessionOutputEntity> table = new LinkedHashMap<>();
        FakeAttachments attachments = new FakeAttachments();
        SessionOutputService svc = new SessionOutputService(fakeRepo(table),
                fakeSessionRepo("liuchangqing"), providerOf(attachments));
        byte[] bin = {0x50, 0x4B, 0x03, 0x04};
        String attId = svc.storeBinary("s1", "指标.xlsx", new ByteArrayInputStream(bin), bin.length,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

        SessionOutputEntity row = table.get("s1:指标.xlsx");
        assertEquals(attId, row.getAttachmentId(), "行应引用附件 id");
        assertNull(row.getContent(), "附件行不存文本正文");
        assertTrue(row.isAttachment());
        assertTrue(svc.findContent("s1", "指标.xlsx").isEmpty(), "附件行文本读取返回 empty");
        assertEquals(AttachmentStore.SCOPE_PRIVATE, attachments.lastScope);
        assertEquals("liuchangqing", attachments.lastOwner);
    }

    @Test
    void 同名附件覆盖删旧附件() {
        Map<String, SessionOutputEntity> table = new LinkedHashMap<>();
        FakeAttachments attachments = new FakeAttachments();
        SessionOutputService svc = new SessionOutputService(fakeRepo(table),
                fakeSessionRepo("u1"), providerOf(attachments));
        String first = svc.storeBinary("s1", "a.xlsx", new ByteArrayInputStream(new byte[]{1}), 1, null);
        String second = svc.storeBinary("s1", "a.xlsx", new ByteArrayInputStream(new byte[]{2}), 1, null);

        assertEquals(second, table.get("s1:a.xlsx").getAttachmentId(), "引用应指向新附件");
        assertEquals(List.of(first), attachments.deleted, "旧附件必须删除（附件管理页不留孤儿）");
        assertEquals(1, attachments.stored.size());
    }

    @Test
    void 文本同名覆盖附件行时清引用并删旧附件() {
        Map<String, SessionOutputEntity> table = new LinkedHashMap<>();
        FakeAttachments attachments = new FakeAttachments();
        SessionOutputService svc = new SessionOutputService(fakeRepo(table),
                fakeSessionRepo("u1"), providerOf(attachments));
        String attId = svc.storeBinary("s1", "a.xlsx", new ByteArrayInputStream(new byte[]{1}), 1, null);
        svc.store("s1", List.of(new OutputFile("a.xlsx", "现在变成文本了")));

        SessionOutputEntity row = table.get("s1:a.xlsx");
        assertNull(row.getAttachmentId(), "文本覆盖后引用应清空");
        assertEquals("现在变成文本了", row.getContent());
        assertEquals(List.of(attId), attachments.deleted, "被覆盖的附件必须删除");
    }
}
