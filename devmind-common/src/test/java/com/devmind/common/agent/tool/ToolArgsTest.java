package com.devmind.common.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** CAP-76 FR-04 / 验收 1：工具参数 schema 子集校验。 */
class ToolArgsTest {

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "projectId": {"type": "string", "maxLength": 8},
                "limit": {"type": "integer", "minimum": 1, "maximum": 50, "default": 20},
                "status": {"type": "string", "enum": ["OPEN", "DONE"]},
                "flags": {"type": "array", "maxItems": 2, "items": {"type": "string"}},
                "meta": {
                  "type": "object",
                  "properties": {"note": {"type": "string"}}
                }
              },
              "required": ["projectId"]
            }
            """;

    @Test
    void acceptsValidAndAppliesDefaults() {
        Map<String, Object> args = ToolArgs.validate(SCHEMA,
                Map.of("projectId", "p1", "status", "DONE"));
        assertEquals("p1", args.get("projectId"));
        assertEquals("DONE", args.get("status"));
        assertEquals(20L, args.get("limit"));
        assertFalse(args.containsKey("flags"));
    }

    @Test
    void rejectsUnknownField() {
        var e = assertThrows(ToolArgs.InvalidArgumentException.class,
                () -> ToolArgs.validate(SCHEMA, Map.of("projectId", "p1", "bogus", 1)));
        assertEquals("参数包含未知字段: bogus", e.getMessage());
    }

    @Test
    void rejectsMissingRequired() {
        var e = assertThrows(ToolArgs.InvalidArgumentException.class,
                () -> ToolArgs.validate(SCHEMA, Map.of()));
        assertEquals("缺少必填字段: projectId", e.getMessage());
    }

    @Test
    void rejectsWrongTypes() {
        assertThrows(ToolArgs.InvalidArgumentException.class,
                () -> ToolArgs.validate(SCHEMA, Map.of("projectId", 1)));
        assertThrows(ToolArgs.InvalidArgumentException.class,
                () -> ToolArgs.validate(SCHEMA, Map.of("projectId", "p1", "limit", 1.5)));
        assertThrows(ToolArgs.InvalidArgumentException.class,
                () -> ToolArgs.validate(SCHEMA, Map.of("projectId", "p1", "flags", "x")));
    }

    @Test
    void rejectsTooLong() {
        var e = assertThrows(ToolArgs.InvalidArgumentException.class,
                () -> ToolArgs.validate(SCHEMA, Map.of("projectId", "123456789")));
        assertEquals("projectId 超过最大长度 8", e.getMessage());
    }

    @Test
    void rejectsOutOfEnumAndBounds() {
        assertThrows(ToolArgs.InvalidArgumentException.class,
                () -> ToolArgs.validate(SCHEMA, Map.of("projectId", "p1", "status", "CLOSED")));
        assertThrows(ToolArgs.InvalidArgumentException.class,
                () -> ToolArgs.validate(SCHEMA, Map.of("projectId", "p1", "limit", 0)));
        assertThrows(ToolArgs.InvalidArgumentException.class,
                () -> ToolArgs.validate(SCHEMA, Map.of("projectId", "p1", "limit", 51)));
        assertThrows(ToolArgs.InvalidArgumentException.class,
                () -> ToolArgs.validate(SCHEMA,
                        Map.of("projectId", "p1", "flags", List.of("a", "b", "c"))));
    }

    @Test
    void validatesNestedObject() {
        Map<String, Object> args = ToolArgs.validate(SCHEMA,
                Map.of("projectId", "p1", "meta", Map.of("note", "hi")));
        assertEquals(Map.of("note", "hi"), args.get("meta"));
        assertThrows(ToolArgs.InvalidArgumentException.class,
                () -> ToolArgs.validate(SCHEMA,
                        Map.of("projectId", "p1", "meta", Map.of("bogus", 1))));
    }

    @Test
    void rejectsBrokenSchema() {
        assertThrows(ToolArgs.InvalidArgumentException.class,
                () -> ToolArgs.validate("{not json", Map.of()));
        assertThrows(ToolArgs.InvalidArgumentException.class,
                () -> ToolArgs.validate("{\"type\":\"array\"}", Map.of()));
    }

    @Test
    void emptySchemaRejectsAnyArgAndAcceptsEmpty() {
        assertEquals(Map.of(), ToolArgs.validate("", Map.of()));
        assertThrows(ToolArgs.InvalidArgumentException.class,
                () -> ToolArgs.validate("{}", Map.of("x", 1)));
    }
}
