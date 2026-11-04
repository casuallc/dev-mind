package com.devmind.security.parse;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-66 FR-11-1：Maven dependency:tree 文本解析——层级/直接依赖判定/引入链；
 * omitted 节点不入索引；同 GA 重复出现保留最浅路径。
 */
class DepTreeParserTest {

    @Test
    void 层级与引入链解析() {
        String tree = """
                [INFO] com.demo:app:jar:1.0.0
                [INFO] +- org.springframework:spring-web:jar:6.1.0:compile
                [INFO] |  +- org.springframework:spring-beans:jar:6.1.0:compile
                [INFO] |  \\- org.springframework:spring-core:jar:6.1.0:compile
                [INFO] \\- junit:junit:jar:4.13.2:test
                """;
        Map<String, DepTreeParser.DepInfo> idx = DepTreeParser.parseMavenTree(tree);

        DepTreeParser.DepInfo web = idx.get("org.springframework:spring-web");
        assertTrue(web.direct());
        assertEquals("compile", web.scope());
        assertEquals(java.util.List.of("org.springframework:spring-web"), web.path());

        DepTreeParser.DepInfo core = idx.get("org.springframework:spring-core");
        assertFalse(core.direct());
        assertEquals(java.util.List.of("org.springframework:spring-web", "org.springframework:spring-core"),
                core.path());

        DepTreeParser.DepInfo junit = idx.get("junit:junit");
        assertTrue(junit.direct());
        assertEquals("test", junit.scope());

        assertNull(idx.get("com.demo:app")); // 根模块自身不入索引
    }

    @Test
    void omitted节点不入索引且重复GA保留最浅() {
        String tree = """
                com.demo:app:jar:1.0.0
                +- com.a:a-lib:jar:1.0:compile
                |  +- com.b:b-lib:jar:2.0:compile
                |  \\- (com.c:c-lib:jar:3.0:compile - omitted for conflict)
                \\- com.c:c-lib:jar:3.0:compile
                """;
        Map<String, DepTreeParser.DepInfo> idx = DepTreeParser.parseMavenTree(tree);
        DepTreeParser.DepInfo c = idx.get("com.c:c-lib");
        assertTrue(c.direct()); // 取的是直接依赖那条（最浅），不是 omitted 那条
        assertEquals(java.util.List.of("com.c:c-lib"), c.path());
    }

    @Test
    void 空文本与无INFO前缀均可用() {
        assertTrue(DepTreeParser.parseMavenTree(null).isEmpty());
        assertTrue(DepTreeParser.parseMavenTree("  \n ").isEmpty());
        Map<String, DepTreeParser.DepInfo> idx = DepTreeParser.parseMavenTree(
                "com.demo:app:jar:1.0.0\n+- com.x:x:jar:1.0:provided\n");
        assertEquals("provided", idx.get("com.x:x").scope());
    }
}
