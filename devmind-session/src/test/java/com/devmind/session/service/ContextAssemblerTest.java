package com.devmind.session.service;

import com.devmind.common.agent.exec.ContextAssemblyRequest;
import com.devmind.common.agent.exec.ContextContribution;
import com.devmind.common.agent.exec.ContextPackage;
import com.devmind.common.agent.exec.ContextPackages;
import com.devmind.common.agent.exec.ContextProvider;
import com.devmind.common.agent.exec.ManifestItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ContextAssembler}（CAP-33 FR-02/FR-07）：节序拼接（场景背景 → provider 节 → 当前任务）、
 * 空产出 = null、settings 取第一个非空、快照 JSON 只存清单不存内容。
 * （三层合并/去重在各 provider 内完成，见 knowledge/docs/skill 各自测试。）
 */
class ContextAssemblerTest {

    private static final ContextAssemblyRequest REQ = new ContextAssemblyRequest(
            "p1", List.of("java"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            true, false);

    private static ContextProvider provider(ContextContribution c) {
        return req -> c;
    }

    @Test
    void 节序与空产出() {
        // 全空产出 → null（沿用「知识无命中 = 无注入」语义）
        ContextAssembler none = assembler(provider(ContextContribution.empty()));
        assertNull(none.assemble(REQ, null, null, null, "任务"));

        ContextAssembler a = assembler(
                provider(new ContextContribution(List.of("\n---\n\n## 通用经验\n\nA\n"),
                        List.of(), List.of(), "{}", List.of())),
                provider(new ContextContribution(List.of("\n---\n\n## 项目文档\n\nB\n"),
                        List.of(), List.of(), null, List.of())));
        ContextAssembler.AssembledContext r = a.assemble(REQ, "code-review", "评审场景", "背景口径", "修 bug");
        String md = r.pkg().claudeMd();
        // 场景背景 < 通用经验 < 项目文档 < 当前任务
        assertTrue(md.indexOf("## 场景背景") < md.indexOf("## 通用经验"));
        assertTrue(md.indexOf("## 通用经验") < md.indexOf("## 项目文档"));
        assertTrue(md.indexOf("## 项目文档") < md.indexOf("## 当前任务"));
        assertTrue(md.contains("背景口径"));
        assertTrue(md.endsWith("修 bug\n"));
        assertEquals("{}", r.pkg().settingsLocalJson()); // 第一个非空 settings 生效
    }

    @Test
    void skills与docs汇聚且manifest摘要一致() {
        ContextPackage.SkillPackage skill = new ContextPackage.SkillPackage("s1",
                Map.of("SKILL.md", Base64.getEncoder().encodeToString("x".getBytes(StandardCharsets.UTF_8))));
        ContextPackage.DocEntry doc = new ContextPackage.DocEntry("7", "设计稿", "全文");
        ManifestItem item = new ManifestItem(ManifestItem.KIND_SKILL, "s1", "s1", "GLOBAL",
                ManifestItem.SOURCE_SCENARIO, null);
        ContextAssembler a = assembler(provider(new ContextContribution(
                List.of(), List.of(skill), List.of(doc), null, List.of(item))));
        ContextAssembler.AssembledContext r = a.assemble(REQ, "sc", "场景", null, "任务");

        assertEquals(1, r.pkg().skills().size());
        assertEquals(1, r.pkg().docs().size());
        assertEquals(1, r.manifest().entries());
        byte[] bytes = ContextPackages.toJsonBytes(r.pkg());
        assertEquals(r.manifest().sha256(), ContextPackages.sha256Hex(bytes));
        assertEquals(r.manifest().totalBytes(), bytes.length);
        // 快照只存清单，不泄包内容
        assertTrue(r.snapshotJson().contains("\"scenarioCode\":\"sc\""));
        assertTrue(r.snapshotJson().contains("\"sha256\""));
        org.junit.jupiter.api.Assertions.assertFalse(r.snapshotJson().contains("全文"));
    }

    @Test
    void 仅场景背景也成包() {
        ContextAssembler a = assembler(provider(ContextContribution.empty()));
        ContextAssembler.AssembledContext r = a.assemble(REQ, "sc", "场景", "只有背景", "任务");
        assertTrue(r.pkg().claudeMd().contains("## 场景背景"));
        assertEquals(0, r.manifest().entries());
    }

    private static ContextAssembler assembler(ContextProvider... providers) {
        ObjectProvider<ContextProvider> op = (ObjectProvider<ContextProvider>) Proxy.newProxyInstance(
                ContextAssemblerTest.class.getClassLoader(),
                new Class<?>[]{ObjectProvider.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "orderedStream" -> Stream.of(providers);
                    case "getIfAvailable" -> providers.length > 0 ? providers[0] : null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return new ContextAssembler(op, JsonMapper.builder().build());
    }
}
