package com.devmind.decisionlab.checkpoint;

import com.devmind.common.model.LayaDecisionClient;
import com.devmind.common.model.ModelEndpointProvider;
import com.devmind.common.model.ModelEndpointView;
import com.devmind.decisionlab.checkpoint.dto.ServeCheckResult;
import com.devmind.decisionlab.checkpoint.model.DecisionCheckpointEntity;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CAP-56 FR-06 的来源一致检查：边车<b>实际</b>加载的来源 vs 库里登记的 {@code source_path}。
 *
 * <p>这条检查是 FR-01（边车上报槽位来源）的消费端，也是整条链路里唯一能证明「闸门放行的那份
 * == 正在跑的那份」的地方。所以这里最要紧的两条断言是：
 * <ul>
 *   <li><b>对不上必须 FAIL</b>——槽位名对、里面装的却是别的东西（节点上有人改过 models.json），
 *       只看"槽位常驻"是看不出来的；</li>
 *   <li><b>老版本边车没报来源时是 WARN 而不是 OK</b>——缺的检查不许显示成一个恒绿的"来源一致"，
 *       那等于把"没核对过"卖成"核对过了"。</li>
 * </ul>
 */
class ServeCheckServiceTest {

    private static final String SLOT = "typed-decisions";

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static DecisionCheckpointEntity row(String sourcePath) {
        DecisionCheckpointEntity e = new DecisionCheckpointEntity();
        e.setName("ft7");
        e.setServeSlot(SLOT);
        e.setSourcePath(sourcePath);
        return e;
    }

    private static LayaDecisionClient.SlotSource local(String path, Boolean ready, List<String> missing) {
        return new LayaDecisionClient.SlotSource(path, "local", true, true, path, null, ready, missing,
                null, "cuda:0");
    }

    private static LayaDecisionClient.Health health(LayaDecisionClient.SlotSource source) {
        Map<String, LayaDecisionClient.SlotSource> sources = new LinkedHashMap<>();
        if (source != null) {
            sources.put(SLOT, source);
        }
        return new LayaDecisionClient.Health("ok", "0.3.5", List.of(SLOT), Map.of(SLOT, "cuda:0"), sources);
    }

    // ---------------- 纯比对逻辑 ----------------

    @Test
    void matchingLocalSourceIsOk() {
        ServeCheckResult.Check c = ServeCheckService.sourceCheck(row("/data/laya/ft7"),
                health(local("/data/laya/ft7", true, List.of())));

        assertEquals(ServeCheckResult.OK, c.status());
        assertTrue(c.detail().contains("/data/laya/ft7"), c.detail());
        assertTrue(c.detail().contains("已被覆盖"), c.detail());   // 覆盖状态一并交代，好看出"不是内置默认"
    }

    @Test
    void windowsPathDifferencesAreNotMismatches() {
        // 登记时手敲的 C:\… 与边车回的 C:/…（大小写与斜杠方向）必须算同一份，否则噪音淹没真问题
        ServeCheckResult.Check c = ServeCheckService.sourceCheck(
                row("D:\\Apusic\\Laya\\ft7\\"),
                health(local("d:/apusic/laya/ft7", true, List.of())));

        assertEquals(ServeCheckResult.OK, c.status(), c.detail());
    }

    @Test
    void anotherArtifactInTheSameSlotFails() {
        // 槽位名对得上、里面装的却是上一份产物 —— 只看"槽位常驻"永远看不出来
        ServeCheckResult.Check c = ServeCheckService.sourceCheck(row("/data/laya/ft7"),
                health(local("/data/laya/ft6", true, List.of())));

        assertEquals(ServeCheckResult.FAIL, c.status());
        assertTrue(c.detail().contains("/data/laya/ft6"), c.detail());
        assertTrue(c.detail().contains("/data/laya/ft7"), c.detail());
        assertTrue(c.detail().contains("不是正在服务的那份"), c.detail());
    }

    @Test
    void localDirectoryMissingModelFilesFails() {
        // 目录对得上但文件不全：模型压根加载不出来，此刻服务的是别的
        ServeCheckResult.Check c = ServeCheckService.sourceCheck(row("/data/laya/ft7"),
                health(local("/data/laya/ft7", false, List.of("model.safetensors"))));

        assertEquals(ServeCheckResult.FAIL, c.status());
        assertTrue(c.detail().contains("model.safetensors"), c.detail());
    }

    @Test
    void repoRegistrationAlsoMatchesWhenTheSidecarLoadsASubfolder() {
        // 登记到仓库一级、边车加载的是它的子目录：子目录是边车解析来源的细节，算一致
        LayaDecisionClient.SlotSource src = new LayaDecisionClient.SlotSource(
                "convaiinnovations/laya/typed-decisions", "repo", false, true, null, "typed-decisions",
                null, List.of(), "convaiinnovations/laya", null);
        ServeCheckResult.Check c = ServeCheckService.sourceCheck(row("convaiinnovations/laya"),
                health(src));

        assertEquals(ServeCheckResult.OK, c.status(), c.detail());
    }

    @Test
    void repoRegistrationDoesNotMatchADifferentRepo() {
        LayaDecisionClient.SlotSource src = new LayaDecisionClient.SlotSource(
                "convaiinnovations/laya-multilingual", "repo", false, true, null, null,
                null, List.of(), "convaiinnovations/laya-multilingual", null);

        assertEquals(ServeCheckResult.FAIL,
                ServeCheckService.sourceCheck(row("convaiinnovations/laya"), health(src)).status());
    }

    @Test
    void oldSidecarWithoutSourcesWarnsInsteadOfPretendingToPass() {
        ServeCheckResult.Check c = ServeCheckService.sourceCheck(row("/data/laya/ft7"), health(null));

        assertEquals(ServeCheckResult.WARN, c.status());
        assertTrue(c.detail().contains("老版本边车"), c.detail());
    }

    @Test
    void missingRegisteredSourceWarnsRatherThanGuessing() {
        assertEquals(ServeCheckResult.WARN,
                ServeCheckService.sourceCheck(row("  "), health(local("/data/laya/ft7", true, List.of()))).status());
    }

    // ---------------- 接线：这一条真的出现在报告里 ----------------

    @Test
    void runReportsTheSourceCheckAndKeepsRawSources() throws IOException {
        // 真起一个假边车打 /healthz：验的是"跑完一遍之后，来源一致这一条在不在报告里"
        String json = "{\"status\":\"ok\",\"laya_version\":\"0.3.5\",\"loaded\":[\"" + SLOT + "\"],"
                + "\"devices\":{\"" + SLOT + "\":\"cuda:0\"},"
                + "\"sources\":{\"" + SLOT + "\":{\"source\":\"/data/laya/ft6\",\"kind\":\"local\","
                + "\"overridden\":true,\"loaded\":true,\"path\":\"/data/laya/ft6\",\"ready\":true,"
                + "\"missing\":[]}}}";
        String baseUrl = serve(json);

        ServeCheckResult result = service(baseUrl).run(row("/data/laya/ft7"));

        assertEquals(ServeCheckResult.FAIL, result.status());
        List<String> items = result.checks().stream().map(ServeCheckResult.Check::item).toList();
        assertTrue(items.contains("来源一致"), items.toString());
        assertTrue(items.contains("槽位常驻") && items.contains("设备"), items.toString());
        // 报告的 sources 是边车自报原值（排错要看"它到底从哪加载的"），不是我们加工后的结论
        assertTrue(result.report().toString().contains("/data/laya/ft6"), result.report().toString());
    }

    private static String serve(String healthJson) throws IOException {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/healthz", ex -> {
            byte[] bytes = healthJson.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        srv.start();
        return "http://127.0.0.1:" + srv.getAddress().getPort();
    }

    @SuppressWarnings("unchecked")
    private ServeCheckService service(String baseUrl) {
        ModelEndpointView view = new ModelEndpointView(1L, ModelEndpointView.KIND_DECISION,
                ModelEndpointView.PROVIDER_LAYA, "边车", baseUrl, null, null, null, 10, 10, null, null);
        ModelEndpointProvider provider = mock(ModelEndpointProvider.class);
        when(provider.defaultEndpoint(anyString())).thenReturn(Optional.of(view));
        ObjectProvider<ModelEndpointProvider> providers = mock(ObjectProvider.class);
        when(providers.getIfAvailable()).thenReturn(provider);
        return new ServeCheckService(providers);
    }
}
