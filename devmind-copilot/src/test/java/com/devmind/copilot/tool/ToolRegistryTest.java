package com.devmind.copilot.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.agent.tool.ToolRisk;
import com.devmind.common.agent.tool.ToolSpec;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** CAP-76 FR-03 / 验收 10：注册表校验、角色过滤与 OpenAPI 导出。 */
class ToolRegistryTest {

    private static PlatformTool tool(String name, ToolRisk risk, String minRole) {
        ToolSpec spec = new ToolSpec(name, "工具 " + name, "说明", "{\"type\":\"object\"}",
                risk, minRole, false, "test", "CAP-76 M1");
        return PlatformTool.of(spec, (call, ctx) -> ToolResult.ok("ok"));
    }

    private static ToolRegistry registry(PlatformTool... tools) {
        return new ToolRegistry(List.of(tools), new ObjectMapper());
    }

    @Test
    void registersAndFiltersByRole() {
        ToolRegistry registry = registry(
                tool("read_tool", ToolRisk.READ, "VIEWER"),
                tool("write_tool", ToolRisk.WRITE, "DEVELOPER"),
                tool("admin_tool", ToolRisk.WRITE, "ADMIN"));

        assertEquals(3, registry.all().size());
        // Map.copyOf 不保序，按集合比较
        assertEquals(Set.of("read_tool"), Set.copyOf(names(registry.visibleTo("VIEWER"))));
        assertEquals(Set.of("read_tool", "write_tool"),
                Set.copyOf(names(registry.visibleTo("DEVELOPER"))));
        assertEquals(Set.of("read_tool", "write_tool", "admin_tool"),
                Set.copyOf(names(registry.visibleTo("ADMIN"))));
        // 未知角色按 VIEWER 处理，不能借大小写/空值提权
        assertEquals(Set.of("read_tool"), Set.copyOf(names(registry.visibleTo("root"))));
        assertEquals(Set.of("read_tool"), Set.copyOf(names(registry.visibleTo(null))));
        assertTrue(registry.find("read_tool").isPresent());
        assertFalse(registry.find("nope").isPresent());
    }

    @Test
    void rejectsDuplicateNames() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> registry(tool("dup", ToolRisk.READ, "VIEWER"),
                        tool("dup", ToolRisk.READ, "VIEWER")));
        assertEquals("工具名重复: dup", e.getMessage());
    }

    @Test
    void rejectsInvalidNameAndRole() {
        assertThrows(IllegalStateException.class,
                () -> registry(tool("Bad-Name", ToolRisk.READ, "VIEWER")));
        assertThrows(IllegalStateException.class,
                () -> registry(tool("tool", ToolRisk.READ, "ROOT")));
    }

    @Test
    void rejectsUnparsableSchema() {
        ToolSpec spec = new ToolSpec("broken", "坏工具", "说明", "{oops",
                ToolRisk.READ, "VIEWER", false, "test", "CAP-76 M1");
        PlatformTool t = PlatformTool.of(spec, (call, ctx) -> ToolResult.ok("ok"));
        assertThrows(IllegalStateException.class, () -> registry(t));
    }

    @Test
    void openApiHasOneOperationPerTool() {
        ToolRegistry registry = registry(
                tool("read_tool", ToolRisk.READ, "VIEWER"),
                tool("write_tool", ToolRisk.WRITE, "DEVELOPER"));
        Map<String, Object> doc = registry.openApi();

        assertEquals("3.0.3", doc.get("openapi"));
        @SuppressWarnings("unchecked")
        Map<String, Object> paths = (Map<String, Object>) doc.get("paths");
        assertEquals(2, paths.size());
        assertTrue(paths.containsKey("/tools/read_tool"));
        assertTrue(paths.containsKey("/tools/write_tool"));
        @SuppressWarnings("unchecked")
        Map<String, Object> operation =
                (Map<String, Object>) ((Map<String, Object>) paths.get("/tools/read_tool")).get("post");
        assertEquals("read_tool", operation.get("operationId"));
        assertEquals("READ", operation.get("x-tool-risk"));
    }

    private static List<String> names(List<PlatformTool> tools) {
        return tools.stream().map(t -> t.spec().name()).toList();
    }
}
