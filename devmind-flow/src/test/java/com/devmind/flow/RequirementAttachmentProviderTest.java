package com.devmind.flow;

import com.devmind.common.agent.exec.ContextAssemblyRequest;
import com.devmind.common.agent.exec.ContextContribution;
import com.devmind.common.attachment.AttachmentContentResolver;
import com.devmind.common.attachment.IssueAttachmentResolver;
import com.devmind.project.RequirementService;
import com.devmind.project.model.RequirementEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RequirementAttachmentProvider 单测（无 Spring 上下文）：本地附件引用解析、Jira wiki 图解析、
 * 不可用降级标注、上限截断、无引用空产出。
 */
class RequirementAttachmentProviderTest {

    private RequirementEntity requirement;

    private final RequirementAttachmentProvider provider = new RequirementAttachmentProvider(
            new FakeRequirementService(),
            fixedProvider(new FakeLocalResolver()),
            fixedProvider(new FakeJiraResolver()));

    @Test
    void emptyWhenNoRequirementOrNoRefs() {
        ContextContribution none = provider.contribute(req(null));
        assertTrue(none.inputs().isEmpty());

        requirement = entity("需求无引用描述", false);
        ContextContribution noRefs = provider.contribute(req("r1"));
        assertTrue(noRefs.inputs().isEmpty());
        assertTrue(noRefs.claudeMdSections().isEmpty());
    }

    @Test
    void resolvesLocalAttachmentRefs() {
        requirement = entity("看这张图 ![架构](/api/attachments/0123456789abcdef0123456789abcdef/raw"
                + "?access_token=x) 和重复的 ![](/api/attachments/0123456789abcdef0123456789abcdef/raw)", false);
        ContextContribution c = provider.contribute(req("r1"));
        assertEquals(1, c.inputs().size());
        assertEquals("0123456789abcdef0123456789abcdef.png", c.inputs().get(0).path());
        assertEquals("image/png", c.inputs().get(0).contentType());
        assertEquals("png-bytes",
                new String(Base64.getDecoder().decode(c.inputs().get(0).base64()), StandardCharsets.UTF_8));
        assertEquals(1, c.items().size());
        String section = c.claudeMdSections().get(0);
        assertTrue(section.contains("## 需求附件"), section);
        assertTrue(section.contains(".devmind/input/0123456789abcdef0123456789abcdef.png"), section);
    }

    @Test
    void marksMissingLocalAttachmentUnavailable() {
        requirement = entity("![没了](/api/attachments/ffffffffffffffffffffffffffffffff/raw)", false);
        ContextContribution c = provider.contribute(req("r1"));
        assertTrue(c.inputs().isEmpty());
        assertTrue(c.claudeMdSections().get(0).contains("不可用"), c.claudeMdSections().get(0));
    }

    @Test
    void resolvesJiraWikiImageRefs() {
        requirement = entity("第一步截图 !shot one.png! 再看 !arch.png|width=300!，外链 !http://x/y.png! 不拉",
                true);
        ContextContribution c = provider.contribute(req("r1"));
        assertEquals(2, c.inputs().size());
        assertEquals("jira-ADMQ-1-shot_one.png", c.inputs().get(0).path());
        assertEquals("jira-ADMQ-1-arch.png", c.inputs().get(1).path());
        String section = c.claudeMdSections().get(0);
        assertTrue(section.contains("Jira issue ADMQ-1"), section);
        assertTrue(c.items().stream().anyMatch(i -> ("jira:ADMQ-1:arch.png").equals(i.ref())));
    }

    @Test
    void jiraResolverMissIsUnavailableNotFatal() {
        requirement = entity("!ghost.png!", true);
        ContextContribution c = provider.contribute(req("r1"));
        assertTrue(c.inputs().isEmpty());
        assertTrue(c.claudeMdSections().get(0).contains("不可用"), c.claudeMdSections().get(0));
    }

    @Test
    void 推送转托管后本地附件引用仍被投送() {
        // CAP-47 回归：「推送到 Jira」把 source 翻成 JIRA，但描述里的 Markdown 本地附件链接原样保留；
        // 原先按 source 二选一只扫 wiki 标记，这些附件会在会话上下文里静默消失
        requirement = entity("本地图 ![](/api/attachments/0123456789abcdef0123456789abcdef/raw)"
                + " 与 Jira 图 !arch.png! 混排", true);
        ContextContribution c = provider.contribute(req("r1"));
        assertEquals(2, c.inputs().size());
        // 两类混排仍按文档出现顺序物化
        assertEquals("0123456789abcdef0123456789abcdef.png", c.inputs().get(0).path());
        assertEquals("jira-ADMQ-1-arch.png", c.inputs().get(1).path());
        assertTrue(c.items().stream().anyMatch(i -> "0123456789abcdef0123456789abcdef".equals(i.ref())));
        assertTrue(c.items().stream().anyMatch(i -> "jira:ADMQ-1:arch.png".equals(i.ref())));
    }

    @Test
    void 本地需求描述里的wiki标记走Jira分支而非被静默忽略() {
        // 反向同理：LOCAL 需求里的 !x! 也尝试 Jira 分支（拿不到就标不可用），不再按 source 丢弃
        requirement = entity("!ghost.png! 与 ![a](/api/attachments/0123456789abcdef0123456789abcdef/raw)",
                false);
        ContextContribution c = provider.contribute(req("r1"));
        assertEquals(1, c.inputs().size());
        assertEquals("0123456789abcdef0123456789abcdef.png", c.inputs().get(0).path());
        assertTrue(c.claudeMdSections().get(0).contains("不可用"), c.claudeMdSections().get(0));
    }

    @Test
    void 同类引用去重但跨类同名不去重() {
        requirement = entity("![a](/api/attachments/0123456789abcdef0123456789abcdef/raw) 重复"
                + " ![a](/api/attachments/0123456789abcdef0123456789abcdef/raw) 与 !same.png! 及其重复 !same.png!",
                true);
        ContextContribution c = provider.contribute(req("r1"));
        assertEquals(2, c.inputs().size());
        assertEquals("0123456789abcdef0123456789abcdef.png", c.inputs().get(0).path());
        assertEquals("jira-ADMQ-1-same.png", c.inputs().get(1).path());
    }

    @Test
    void capsFileCountAndNotesOmission() {
        StringBuilder desc = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            desc.append("![f").append(i).append("](/api/attachments/")
                    .append(String.format("%032x", i + 1)).append("/raw)\n");
        }
        requirement = entity(desc.toString(), false);
        ContextContribution c = provider.contribute(req("r1"));
        assertEquals(10, c.inputs().size());
        assertTrue(c.claudeMdSections().get(0).contains("已省略"), c.claudeMdSections().get(0));
        assertEquals(10, c.items().size());
    }

    // ---------------- fakes ----------------

    private ContextAssemblyRequest req(String requirementId) {
        return new ContextAssemblyRequest("p1", List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), true, false, requirementId);
    }

    private static RequirementEntity entity(String description, boolean jira) {
        RequirementEntity e = new RequirementEntity();
        e.setId("r1");
        e.setProjectId("p1");
        e.setDescription(description);
        e.setSource(jira ? RequirementEntity.SOURCE_JIRA : "LOCAL");
        return e;
    }

    private class FakeRequirementService extends RequirementService {
        FakeRequirementService() {
            super(null, null, null, null, null, null, null, null, null);
        }

        @Override
        public RequirementEntity requireById(String requirementId) {
            if (requirement == null) {
                throw new IllegalStateException("not found");
            }
            return requirement;
        }
    }

    /** 本地附件：id 以 f 开头视为缺失；其余返回 png 字节。 */
    private static class FakeLocalResolver implements AttachmentContentResolver {
        @Override
        public Optional<ResolvedAttachment> resolve(String attachmentId) {
            return resolveAny(attachmentId);
        }

        @Override
        public Optional<ResolvedAttachment> resolveAny(String attachmentId) {
            if (attachmentId.startsWith("f")) {
                return Optional.empty();
            }
            return Optional.of(new ResolvedAttachment(attachmentId, "image/png",
                    "png-bytes".getBytes(StandardCharsets.UTF_8)));
        }
    }

    /** Jira 附件：ghost.png 缺失；其余返回 jpeg 字节。 */
    private static class FakeJiraResolver implements IssueAttachmentResolver {
        @Override
        public Optional<IssueAttachment> resolve(String requirementId, String filename) {
            if ("ghost.png".equals(filename)) {
                return Optional.empty();
            }
            return Optional.of(new IssueAttachment(filename, "image/jpeg",
                    "jpg".getBytes(StandardCharsets.UTF_8), "ADMQ-1"));
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
