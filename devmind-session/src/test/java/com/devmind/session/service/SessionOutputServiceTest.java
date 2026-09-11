package com.devmind.session.service;

import com.devmind.common.agent.SessionOutputSink.OutputFile;
import com.devmind.session.model.SessionOutputEntity;
import com.devmind.session.repo.SessionOutputRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SessionOutputService 存取单测：store 新增/同名覆盖（幂等）、findContent 按会话+文件名读取。
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

    @Test
    void store新增后可按文件名读取() {
        Map<String, SessionOutputEntity> table = new LinkedHashMap<>();
        SessionOutputService svc = new SessionOutputService(fakeRepo(table));
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
        SessionOutputService svc = new SessionOutputService(fakeRepo(table));
        svc.store("s1", List.of(new OutputFile("analysis.md", "v1")));
        svc.store("s1", List.of(new OutputFile("analysis.md", "v2")));
        assertEquals("v2", svc.findContent("s1", "analysis.md").orElseThrow());
        assertEquals(1, table.size(), "同 sessionId+fileName 只留最新一份");
    }
}
