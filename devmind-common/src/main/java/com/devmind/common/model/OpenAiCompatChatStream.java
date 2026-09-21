package com.devmind.common.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP-49 OpenAI 兼容 {@code /chat/completions} <b>流式</b>调用（vLLM / OneAPI / Ollama / OpenAI 等同协议通用）。
 *
 * <p>与探针 {@link OpenAiCompatChat} 刻意分家：探针的回复要「压空白 + 截 200 字」才进
 * {@code last_test_message}，那套处理用在正文上就是内容损坏。本类是真实问答的正文通道——
 * 支持 system + 多轮 messages，返回<b>完整正文</b>（不压空白、不截断），增量经回调逐段吐出。</p>
 *
 * <h2>取消与超时（本类最要紧的设计）</h2>
 * <p><b>不设 {@code HttpRequest.timeout()}</b>：本机 JDK 21 上该超时在<b>响应头到达</b>时即被
 * {@code MultiExchange} 取消，救不了"流卡住了"；而 JDK-8370631 已把它扩到"响应体消费完成为止"
 * （未来 JDK 会开始腰斩长回复）。语义随版本漂移的东西不能作为设计依赖，改由应用层三条看门狗收口：
 * 首字节 / 分片停顿 / 整回合上限——超时一律 {@code Thread.interrupt()} 读取线程。</p>
 *
 * <p><b>取消 = 中断读线程</b>：{@code BodyHandlers.ofInputStream()} 的 {@code @implNote} 把这件事写成了
 * 契约——"中断阻塞中的读线程会抛 {@code IOException}，同时取消请求并关闭流"。因此不需要"另起线程
 * close InputStream"那种碰运气做法（那种做法在"还没拿到流"时根本无处下手）。</p>
 *
 * <p>其余边界一律按"网关会怎么偷懒"来设计：忽略 {@code stream:true} 回普通 JSON → 回落非流式；
 * {@code [DONE]} 缺失照常收尾；同一事件多个 {@code data:} 行按 SSE 规范以 {@code \n} 先拼接；
 * {@code content} 是数组（OneAPI/vLLM 兼容层）→ 拼接全部 text part；{@code reasoning_content}
 * 只累计不进正文（空正文失败时用来解释成因）。</p>
 */
public final class OpenAiCompatChatStream {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 正文累积上限：超过即停止读取（防跑飞），返回 {@code truncated=true} 由调用方决定怎么告知用户。 */
    public static final int MAX_ANSWER_CHARS = 200_000;

    /** 非流式回落的原文缓冲上限（只可能是"忽略 stream 的小 JSON"，不需要留全量） */
    private static final int RAW_CAP = 64 * 1024;

    private OpenAiCompatChatStream() {
    }

    /**
     * 一轮流式对话的全部外部输入。
     *
     * @param connectTimeoutSeconds 连接超时（取端点 timeoutSeconds；也是 socket 连接的上限）
     * @param firstByteTimeoutSeconds 响应头等待上限
     * @param stallTimeoutSeconds   分片停顿上限（多久没有新内容即判卡死）
     * @param turnMaxSeconds        整回合上限（兜底）
     */
    public record Options(String baseUrl, String apiKey, String model,
                          int connectTimeoutSeconds, int firstByteTimeoutSeconds,
                          int stallTimeoutSeconds, int turnMaxSeconds) {

        /**
         * 由端点超时值推默认看门狗：连接/首字节用端点值以外的固定档（首字节 60s / 停顿 120s / 整回合 600s）。
         * 「思考型」模型首字节可能很久，「长文」分片之间也不匀，这三条是兜底而非精细控制。
         */
        public static Options of(String baseUrl, String apiKey, String model, int timeoutSeconds) {
            int connect = timeoutSeconds > 0 ? timeoutSeconds : 30;
            return new Options(baseUrl, apiKey, model, connect,
                    Math.max(connect, 60), Math.max(connect, 120), 600);
        }
    }

    /** 一条对话消息（role: system | user | assistant；本期无 tool）。 */
    public record Message(String role, String content) {
        public static Message system(String content) { return new Message("system", content); }
        public static Message user(String content) { return new Message("user", content); }
        public static Message assistant(String content) { return new Message("assistant", content); }
    }

    /** 一轮结果：正文 + 是否因超长被截断（截断判定由本类给出，调用方不必猜长度阈值）。 */
    public record Reply(String text, boolean truncated) {
    }

    /** 增量回调：{@code text} 是本次新增正文（非累计）。实现必须快速返回——上游读循环是 lock-step 背压。 */
    public interface DeltaListener {
        void onDelta(String text);
    }

    /**
     * 发一轮多轮对话，流式回调增量，返回完整正文。网络/非 2xx/结构异常一律抛
     * {@link ModelCallException}（消息已脱敏）；被中断抛 {@link ModelInterruptedException}（不会被当成故障）。
     */
    public static Reply chatStream(Options opt, List<Message> messages, DeltaListener onDelta) {
        String base = opt.baseUrl() == null ? "" : opt.baseUrl().replaceAll("/+$", "");
        URI uri = URI.create(base + "/chat/completions");
        HttpRequest.Builder req = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                // 刻意不设 .timeout(...)：见类注释——响应头之后它就失效，靠下面的看门狗收口
                .POST(HttpRequest.BodyPublishers.ofString(bodyOf(opt, messages)));
        if (opt.apiKey() != null && !opt.apiKey().isBlank()) {
            req.header("Authorization", "Bearer " + opt.apiKey());
        }
        HttpRequest request = req.build();

        Thread turn = Thread.currentThread();
        AtomicBoolean headersArrived = new AtomicBoolean(false);
        AtomicLong progressAt = new AtomicLong(System.currentTimeMillis());
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicReference<String> timeoutReason = new AtomicReference<>();
        Thread watcher = watchdog(opt, turn, timeoutReason, headersArrived, progressAt, finished);

        try {
            HttpResponse<InputStream> resp = OpenAiCompatHttp.http(opt.connectTimeoutSeconds())
                    .send(request, HttpResponse.BodyHandlers.ofInputStream());
            headersArrived.set(true);
            progressAt.set(System.currentTimeMillis());
            if (resp.statusCode() / 100 != 2) {
                // 非 2xx 直接失败：流式错误体照旧走同一套脱敏 + URL 回显 + 404 成因提示
                throw new ModelCallException(OpenAiCompatHttp.failure(
                        "chat 流式端点", request.uri(), resp.statusCode(), readSnippet(resp.body())));
            }
            return readStream(resp, onDelta, progressAt);
        } catch (ModelCallException e) {
            throw e;
        } catch (InterruptedException e) {
            // ofInputStream 的契约：中断读线程 → 取消请求 + 关流 + 抛 IOException；send 阶段则直接抛本异常
            throw interruptedOrTimeout(timeoutReason);
        } catch (IOException e) {
            // JDK 在中断阻塞读时会把中断位置回来再抛 IOException，故先看中断位、再看看门狗原因
            if (timeoutReason.get() != null) {
                throw timeout(timeoutReason.get());
            }
            if (Thread.interrupted()) {
                throw new ModelInterruptedException("对话生成已中断");
            }
            throw new ModelCallException("对话流式调用失败: "
                    + OpenAiCompatHttp.sanitize(String.valueOf(e)), e);
        } finally {
            finished.set(true);
            watcher.interrupt();
            // 清中断位：读循环已收尾，别让中断污染调用方后续的落库/广播
            Thread.interrupted();
        }
    }

    // ---------------- 读循环 ----------------

    private static Reply readStream(HttpResponse<InputStream> resp, DeltaListener onDelta,
                                    AtomicLong progressAt) throws IOException {
        Ctx ctx = new Ctx();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            // SSE 按「事件」而不是「行」解析：同一事件的多个 data 行要用 \n 拼回原文再解析（长 JSON 会被代理拆行）
            StringBuilder data = new StringBuilder();
            boolean hasData = false;
            String line;
            while ((line = reader.readLine()) != null) {
                progressAt.set(System.currentTimeMillis());
                if (!ctx.sawEvent && ctx.raw.length() < RAW_CAP) {
                    ctx.raw.append(line).append('\n');
                }
                if (line.isEmpty()) {
                    // 先消费再清账：handleEvent 可能已经改了 ctx（截断/收尾），收尾时不能再喂一遍同一事件
                    boolean stop = hasData && handleEvent(data, ctx, onDelta);
                    data.setLength(0);
                    hasData = false;
                    if (stop) {
                        break;
                    }
                    continue;
                }
                if (line.charAt(0) == ':') {
                    continue;                       // 注释/心跳
                }
                if (line.startsWith("data:")) {
                    String value = line.substring(5);
                    if (value.startsWith(" ")) {
                        value = value.substring(1);
                    }
                    if (hasData) {
                        data.append('\n');          // SSE 规范：多行 data 以 \n 连接
                    }
                    data.append(value);
                    hasData = true;
                }
                // event: / id: / retry: / 其他字段一律忽略
            }
            if (hasData) {
                handleEvent(data, ctx, onDelta);        // 有些网关在 EOF 前不给"事件结束"的空行
            }
        }
        String text = ctx.answer.toString();
        if (!text.isEmpty()) {
            return new Reply(text, ctx.truncated);
        }
        // 一分正文都没拿到：最常见是服务端忽略 stream:true 回了普通 JSON，按整包回落（打字机退化成一跳）
        String fallback = OpenAiCompatHttp.messageText(ctx.raw.toString());
        if (!fallback.isEmpty()) {
            onDelta.onDelta(fallback);
            return new Reply(fallback, false);
        }
        throw new ModelCallException("对话流式端点无回复内容（网关拦截或限流？）"
                + modelHint(ctx) + snippetHint(ctx));
    }

    /** 处理一个 SSE 事件；返回 true = 读到 {@code [DONE]} 或已达正文上限（调用方停止读取）。 */
    private static boolean handleEvent(CharSequence payload, Ctx ctx, DeltaListener onDelta) {
        String text = payload.toString().strip();
        if ("[DONE]".equals(text)) {
            return true;
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(text);
        } catch (Exception e) {
            throw new ModelCallException("对话流式分片不是合法 JSON: "
                    + OpenAiCompatHttp.sanitize(OpenAiCompatHttp.abbreviate(text)));
        }
        JsonNode error = node.path("error");
        if (error.isObject()) {
            // 200 + SSE 里夹错误对象：vLLM 等会这么报"跑到一半出错"
            throw new ModelCallException("对话流式端点返回错误: "
                    + OpenAiCompatHttp.sanitize(OpenAiCompatHttp.abbreviate(error.toString())));
        }
        ctx.sawEvent = true;
        String model = node.path("model").asText("");
        if (!model.isEmpty()) {
            ctx.model = model;
        }
        JsonNode choices = node.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return false;                           // usage 尾分片等无 choices 的帧
        }
        JsonNode first = choices.get(0);
        String reason = first.path("finish_reason").asText("");
        if (!reason.isEmpty()) {
            ctx.finishReason = reason;
        }
        JsonNode delta = first.path("delta");
        String thinking = delta.path("reasoning_content").asText("");
        if (thinking.isEmpty()) {
            thinking = delta.path("reasoning").asText("");
        }
        if (!thinking.isEmpty()) {
            ctx.thinking.append(thinking);          // 思考内容不进正文，只用于失败时解释成因
        }
        String piece = OpenAiCompatHttp.contentText(delta.path("content"));
        if (!piece.isEmpty()) {
            ctx.answer.append(piece);
            onDelta.onDelta(piece);
        }
        if (ctx.answer.length() >= MAX_ANSWER_CHARS) {
            ctx.truncated = true;
            return true;
        }
        return false;
    }

    // ---------------- 看门狗 ----------------

    private static Thread watchdog(Options opt, Thread turn, AtomicReference<String> reason,
                                   AtomicBoolean headersArrived, AtomicLong progressAt, AtomicBoolean finished) {
        long firstByteDeadline = System.currentTimeMillis() + opt.firstByteTimeoutSeconds() * 1000L;
        long turnDeadline = System.currentTimeMillis() + opt.turnMaxSeconds() * 1000L;
        return Thread.ofVirtual().name("chat-stream-watchdog").start(() -> {
            while (!finished.get()) {
                long now = System.currentTimeMillis();
                String why = null;
                if (now >= turnDeadline) {
                    why = "整回合超过 " + opt.turnMaxSeconds() + "s";
                } else if (!headersArrived.get() && now >= firstByteDeadline) {
                    why = "端点 " + opt.firstByteTimeoutSeconds() + "s 内未返回响应";
                } else if (headersArrived.get() && now - progressAt.get() >= opt.stallTimeoutSeconds() * 1000L) {
                    why = "超过 " + opt.stallTimeoutSeconds() + "s 没有新内容";
                }
                if (why != null) {
                    reason.compareAndSet(null, why);
                    turn.interrupt();
                    return;
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    private static ModelCallException interruptedOrTimeout(AtomicReference<String> reason) {
        String why = reason.get();
        if (why != null) {
            return timeout(why);
        }
        return new ModelInterruptedException("对话生成已中断");
    }

    private static ModelCallException timeout(String why) {
        return new ModelCallException("对话生成超时：" + why);
    }

    // ---------------- 杂项 ----------------

    private static String bodyOf(Options opt, List<Message> messages) {
        var root = MAPPER.createObjectNode();
        root.put("model", opt.model());
        var arr = root.putArray("messages");
        for (Message m : messages) {
            var node = MAPPER.createObjectNode();
            node.put("role", m.role());
            node.put("content", m.content() == null ? "" : m.content());
            arr.add(node);
        }
        // 刻意不发 max_tokens / stream_options：部分网关对它们 400（CAP-48 FR-11 同一教训），
        // 而正文长度由 MAX_ANSWER_CHARS 与整回合上限兜底。
        root.put("stream", true);
        return MAPPER.writeValueAsString(root);
    }

    /** 非 2xx 时最多读一小段错误体（沿用探针的摘要长度，异常消息不刷屏） */
    private static String readSnippet(InputStream in) {
        try (in) {
            byte[] buf = new byte[OpenAiCompatHttp.SNIPPET_LEN * 2];
            int n = in.readNBytes(buf, 0, buf.length);
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String modelHint(Ctx ctx) {
        if (ctx.thinking.length() > 0) {
            // 思考型模型（vLLM 的 reasoning_content / DeepSeek 系）只吐思考不吐正文时，
            // 光看"零正文"会以为是网关拦截——把真实成因写出来
            return "（只返回了思考内容 " + ctx.thinking.length() + " 字，未返回正文）";
        }
        return ctx.model.isEmpty() ? "" : "（模型 " + ctx.model + "，finish_reason=" + ctx.finishReason + "）";
    }

    private static String snippetHint(Ctx ctx) {
        if (ctx.raw.isEmpty()) {
            return "";
        }
        return " 原文: " + OpenAiCompatHttp.sanitize(
                OpenAiCompatHttp.abbreviate(OpenAiCompatHttp.collapse(ctx.raw.toString())));
    }

    /** 一轮读取的累计状态（正文/思考/回落原文/是否见过事件） */
    private static final class Ctx {
        private final StringBuilder answer = new StringBuilder();
        private final StringBuilder thinking = new StringBuilder();
        private final StringBuilder raw = new StringBuilder();
        private boolean sawEvent;
        private boolean truncated;
        private String finishReason = "";
        private String model = "";
    }
}
