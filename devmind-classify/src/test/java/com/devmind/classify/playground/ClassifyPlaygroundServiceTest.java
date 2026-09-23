package com.devmind.classify.playground;

import com.devmind.classify.config.ClassifyProperties;
import com.devmind.classify.instance.model.ClassifyInstanceEntity;
import com.devmind.classify.instance.repo.ClassifyInstanceRepository;
import com.devmind.classify.playground.dto.PlaygroundRunRequest;
import com.devmind.common.decision.DecisionAnswer;
import com.devmind.common.decision.DecisionEngine;
import com.devmind.common.decision.DecisionRecordSink;
import com.devmind.common.decision.DecisionResult;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.model.ModelEndpointProvider;
import com.devmind.common.model.ModelEndpointView;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAP-57 FR-04 试分类：三通道解析（instanceId 直打 / endpointId 解析 / 缺省决策链）+
 * 决策记录血缘（capability=classify-playground、refId=pg-*）。直打通道用 JDK 内置
 * HttpServer 起假边车（真 HTTP 往返，解析链路全走到）。
 */
class ClassifyPlaygroundServiceTest {

    private HttpServer sidecar;
    private String baseUrl;

    private ClassifyInstanceRepository instanceRepo;
    private ModelEndpointProvider endpointProvider;
    private DecisionEngine engine;
    private DecisionRecordSink sink;
    private ClassifyPlaygroundService service;

    private static final String REPLY_JSON = """
            {"answers":{"q1":{"type":"choice","choice":"keep","confidence":0.87,
              "probabilities":{"keep":0.87,"discard":0.13}}},
             "routing":{"model":"multilingual","reason":"zh content"}}
            """;

    @BeforeEach
    void setUp() throws Exception {
        sidecar = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sidecar.createContext("/v1/predict", ex -> {
            byte[] body = REPLY_JSON.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        sidecar.start();
        baseUrl = "http://127.0.0.1:" + sidecar.getAddress().getPort();

        instanceRepo = mock(ClassifyInstanceRepository.class);
        endpointProvider = mock(ModelEndpointProvider.class);
        engine = mock(DecisionEngine.class);
        sink = mock(DecisionRecordSink.class);
        service = new ClassifyPlaygroundService(instanceRepo, new ClassifyProperties(),
                providerOf(endpointProvider), providerOf(engine), providerOf(sink));
    }

    @AfterEach
    void tearDown() {
        sidecar.stop(0);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T bean) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(bean);
        return p;
    }

    private static Map<String, Map<String, Object>> oneQuestion() {
        return Map.of("q1", Map.of("type", "choice", "instructions", "是否沉淀",
                "criteria", Map.of("keep", "值得", "discard", "不值得")));
    }

    @Test
    void instanceChannelPredictsAndRecords() {
        ClassifyInstanceEntity inst = new ClassifyInstanceEntity();
        inst.setId(5L);
        inst.setName("gpu-8377");
        inst.setBaseUrl(baseUrl);
        when(instanceRepo.findById(5L)).thenReturn(Optional.of(inst));

        var view = service.run(new PlaygroundRunRequest(5L, null,
                Map.of("proposal", "经验"), oneQuestion()));

        assertEquals("keep", view.answers().get("q1").choice());
        assertEquals(0.87, view.answers().get("q1").confidence());
        assertEquals("multilingual", view.routingModel());
        assertTrue(view.recordRefId().startsWith("pg-"), view.recordRefId());
        assertTrue(view.target().contains("gpu-8377"), view.target());

        ArgumentCaptor<String> capability = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> refId = ArgumentCaptor.forClass(String.class);
        verify(sink).saveSuggestion(capability.capture(), refId.capture(), any(), any(), any());
        assertEquals("classify-playground", capability.getValue());
        assertEquals(view.recordRefId(), refId.getValue());
    }

    @Test
    void endpointChannelResolvesDecisionEndpoint() {
        ModelEndpointView ep = new ModelEndpointView(7L, ModelEndpointView.KIND_DECISION,
                "laya", "gpu-decision", baseUrl, null, "multilingual", null, 30, 0, null, null);
        when(endpointProvider.activeEndpoint(7L)).thenReturn(Optional.of(ep));

        var view = service.run(new PlaygroundRunRequest(null, 7L, Map.of(), oneQuestion()));

        assertEquals("keep", view.answers().get("q1").choice());
        assertTrue(view.target().contains("gpu-decision"), view.target());
        verify(sink).saveSuggestion(eq("classify-playground"), any(), any(), any(), any());
    }

    @Test
    void defaultChannelUsesDecisionEngine() {
        DecisionResult stub = DecisionResult.ok(
                Map.of("q1", new DecisionAnswer("choice", "discard", null, null, 0.66, Map.of())),
                "multilingual", "stub", 12);
        when(engine.decide(any(), any())).thenReturn(stub);

        var view = service.run(new PlaygroundRunRequest(null, null, Map.of(), oneQuestion()));

        assertEquals("discard", view.answers().get("q1").choice());
        assertEquals("平台默认决策链", view.target());
        verify(engine).decide(any(), eq(oneQuestion()));
        verify(sink).saveSuggestion(eq("classify-playground"), any(), any(), any(), any());
    }

    @Test
    void instanceAndEndpointAreMutuallyExclusive() {
        var e = assertThrows(DevMindException.class,
                () -> service.run(new PlaygroundRunRequest(5L, 7L, Map.of(), oneQuestion())));
        assertTrue(e.getMessage().contains("互斥"), e.getMessage());
    }

    @Test
    void questionsRequired() {
        var e = assertThrows(DevMindException.class,
                () -> service.run(new PlaygroundRunRequest(null, null, Map.of(), Map.of())));
        assertTrue(e.getMessage().contains("questions"), e.getMessage());
    }

    @Test
    void missingInstanceIs404() {
        when(instanceRepo.findById(99L)).thenReturn(Optional.empty());
        var e = assertThrows(DevMindException.class,
                () -> service.run(new PlaygroundRunRequest(99L, null, Map.of(), oneQuestion())));
        assertTrue(e.getMessage().contains("不存在"), e.getMessage());
    }
}
