package com.devmind.agent.runner;

import com.devmind.common.agent.exec.ContextPackage;
import com.devmind.common.agent.exec.ContextPackages;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ContextPuller}：拉包→校验→物化；大小/sha 不符与 HTTP 错误一律失败（不静默降级）。 */
class ContextPullerTest {

    @TempDir
    Path workDir;

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private RunnerConfig configPointingAt(HttpServer srv) {
        return new RunnerConfig("ws://127.0.0.1:" + srv.getAddress().getPort() + "/ws/agent",
                "tok", "", "acceptEdits", Path.of("."), Map.of(), 4, "claude", Path.of("."));
    }

    private HttpServer serve(int status, byte[] body) throws IOException {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/api/agent/context/", ex -> {
            ex.sendResponseHeaders(status, status == 200 ? body.length : -1);
            if (status == 200) {
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
        srv.start();
        return srv;
    }

    private static JsonNode manifestOf(byte[] body) {
        String json = "{\"entries\":1,\"totalBytes\":" + body.length
                + ",\"sha256\":\"" + ContextPackages.sha256Hex(body) + "\"}";
        return JsonMapper.builder().build().readTree(json);
    }

    @Test
    void pullsVerifiesAndMaterializes() throws Exception {
        byte[] body = ContextPackages.toJsonBytes(ContextPackage.of("## 通用经验\n", "{\"p\":1}"));
        server = serve(200, body);
        ContextPuller.pullAndMaterialize(configPointingAt(server), "s1", manifestOf(body), workDir);
        assertTrue(Files.readString(workDir.resolve("CLAUDE.md"), StandardCharsets.UTF_8)
                .contains("## 通用经验"));
        assertTrue(Files.isRegularFile(workDir.resolve(".claude/settings.local.json")));
    }

    @Test
    void failsOnShaMismatch() throws Exception {
        byte[] body = ContextPackages.toJsonBytes(ContextPackage.of("x", null));
        server = serve(200, body);
        JsonNode bad = JsonMapper.builder().build()
                .readTree("{\"entries\":1,\"totalBytes\":" + body.length + ",\"sha256\":\"" + "0".repeat(64) + "\"}");
        assertThrows(IOException.class, () ->
                ContextPuller.pullAndMaterialize(configPointingAt(server), "s1", bad, workDir));
        assertTrue(Files.notExists(workDir.resolve("CLAUDE.md")), "校验失败不得物化");
    }

    @Test
    void failsOnHttpError() throws Exception {
        server = serve(404, new byte[0]);
        assertThrows(IOException.class, () -> ContextPuller.pullAndMaterialize(
                configPointingAt(server), "s1", manifestOf(new byte[0]), workDir));
    }
}
