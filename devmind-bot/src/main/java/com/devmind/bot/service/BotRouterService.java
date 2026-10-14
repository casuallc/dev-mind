package com.devmind.bot.service;

import com.devmind.auth.security.DevMindPrincipal;
import com.devmind.bot.config.BotProperties;
import com.devmind.bot.model.BotBindingEntity;
import com.devmind.bot.model.BotInteractionEntity;
import com.devmind.bot.repo.BotBindingRepository;
import com.devmind.bot.repo.BotInteractionRepository;
import com.devmind.bot.spi.BotAdapter;
import com.devmind.bot.spi.BotChannelConfigProvider;
import com.devmind.bot.spi.BotMessage;
import com.devmind.bot.spi.BotMessageHandler;
import com.devmind.build.dto.BuildView;
import com.devmind.build.service.BuildService;
import com.devmind.chat.dto.ChatView;
import com.devmind.chat.dto.CreateChatRequest;
import com.devmind.chat.service.ChatManagerService;
import com.devmind.common.agent.SessionEvent;
import com.devmind.common.dto.PageView;
import com.devmind.common.exception.DevMindException;
import com.devmind.deploy.dto.DeploymentView;
import com.devmind.deploy.service.DeploymentService;
import com.devmind.project.ProjectService;
import com.devmind.project.dto.ProjectView;
import com.devmind.session.dto.CreateSessionRequest;
import com.devmind.session.dto.SessionView;
import com.devmind.session.service.SessionManagerService;
import jakarta.annotation.PreDestroy;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * 消息路由（CAP-75 FR-08/09）：入站消息 → 指令/问答 → 回复文本 → 适配器回推。
 *
 * <p>全部业务在 botExecutor（虚拟线程）执行：onMessage 只做去重 + 投递，立即返回
 * （飞书长连接 3s 处理窗口约束）；虚拟线程天然避开 WS 事件链同步 launch 死锁坑。
 * 身份 = binding.actor ?: devmind.bot.default-actor，任务内手工合成 SecurityContext
 * （OpenApiAuthFilter 先例），finally 清理。</p>
 *
 * <p>群聊只响应 @机器人的消息，私聊全响应。</p>
 */
@Service
public class BotRouterService implements BotMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(BotRouterService.class);
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /** FR-12 敏感操作：支持身份映射的 channel 上要求已绑定个人身份 */
    private static final java.util.Set<String> SENSITIVE_COMMANDS =
            java.util.Set.of("bind", "session", "build", "deploy");

    /** 门禁拒绝时的绑定指引（同时落 REJECTED 交互日志） */
    static final String BIND_GUIDANCE = "该操作需要先绑定个人身份：给我发 /bindme 获取你的身份码，"
            + "然后登录平台 设置 → 第三方账号，在飞书集成上粘贴身份码保存";

    private final BotBindingRepository bindingRepo;
    private final BotInteractionRepository interactionRepo;
    private final ChatManagerService chatManager;
    private final SessionManagerService sessionManager;
    private final ProjectService projectService;
    private final BuildService buildService;
    private final DeploymentService deploymentService;
    private final BotProperties props;
    private final ObjectProvider<BotAdapterRegistry> registryProvider;
    private final Map<String, BotChannelConfigProvider> channelConfigs = new HashMap<>();
    private final EventDedup dedup = new EventDedup();
    private final ExecutorService botExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public BotRouterService(BotBindingRepository bindingRepo,
                            BotInteractionRepository interactionRepo,
                            ChatManagerService chatManager,
                            SessionManagerService sessionManager,
                            ProjectService projectService,
                            BuildService buildService,
                            DeploymentService deploymentService,
                            BotProperties props,
                            ObjectProvider<BotAdapterRegistry> registryProvider,
                            List<BotChannelConfigProvider> channelConfigProviders) {
        this.bindingRepo = bindingRepo;
        this.interactionRepo = interactionRepo;
        this.chatManager = chatManager;
        this.sessionManager = sessionManager;
        this.projectService = projectService;
        this.buildService = buildService;
        this.deploymentService = deploymentService;
        this.props = props;
        this.registryProvider = registryProvider;
        for (BotChannelConfigProvider p : channelConfigProviders) {
            channelConfigs.put(p.channel(), p);
        }
    }

    @PreDestroy
    void shutdown() {
        botExecutor.close();
    }

    // ---------------- 入口 ----------------

    @Override
    public void onMessage(BotMessage msg) {
        if (msg == null || msg.channel() == null || msg.externalChatId() == null
                || msg.text() == null || msg.text().isBlank()) {
            return;
        }
        if (!dedup.markIfFirst(msg.channel(), msg.eventId())) {
            log.info("bot 事件去重丢弃: channel={} eventId={}", msg.channel(), msg.eventId());
            // FR-13：dedup 命中也落库（DEDUPED），投递虚拟线程不占事件回调的 3s 窗口
            botExecutor.execute(() -> saveInteraction(newRecord(msg, "DEDUPED",
                    BotInteractionEntity.STATUS_DEDUPED), null));
            return;
        }
        if (BotMessage.CHAT_TYPE_GROUP.equals(msg.chatType()) && !msg.mentioned()) {
            return; // 群聊只响应 @机器人
        }
        botExecutor.execute(() -> dispatch(msg));
    }

    /** 单条消息完整处理（独立虚拟线程）：装配身份 → 门禁/路由 → 落交互日志 → 回推。 */
    private void dispatch(BotMessage msg) {
        long t0 = System.currentTimeMillis();
        BotInteractionEntity rec = newRecord(msg, null, null);
        String replyText;
        try {
            BotBindingEntity binding = findOrCreateBinding(msg);
            String actor = actorOf(msg, binding);
            rec.setActor(actor);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            new DevMindPrincipal(actor, "USER"), null, List.of()));
            CommandParser.Command cmd = CommandParser.parse(msg.text());
            rec.setCommand(cmd == null ? "QA" : cmd.name());
            if (cmd != null && SENSITIVE_COMMANDS.contains(cmd.name()) && rejectedByGate(msg)) {
                rec.setStatus(BotInteractionEntity.STATUS_REJECTED);
                replyText = BIND_GUIDANCE;
            } else {
                replyText = route(msg, binding, cmd);
                rec.setStatus(BotInteractionEntity.STATUS_OK);
            }
        } catch (DevMindException e) {
            rec.setStatus(BotInteractionEntity.STATUS_FAILED);
            rec.setError(cut(e.getMessage(), 512));
            replyText = "操作失败：" + e.getMessage();
        } catch (Exception e) {
            log.error("bot 消息处理失败: channel={} eventId={} err={}",
                    msg.channel(), msg.eventId(), e.getMessage(), e);
            rec.setStatus(BotInteractionEntity.STATUS_FAILED);
            rec.setError(cut(e.toString(), 512));
            replyText = "处理失败，请稍后再试或到平台查看日志";
        } finally {
            SecurityContextHolder.clearContext();
        }
        rec.setDurationMs(System.currentTimeMillis() - t0);
        saveInteraction(rec, replyText);
        reply(msg, replyText);
    }

    /** FR-13 落库（replyText 截断 4000 一并写入）；落库失败只告警，不影响回推。 */
    private void saveInteraction(BotInteractionEntity rec, String replyText) {
        rec.setReplyText(cut(replyText, 4000));
        try {
            interactionRepo.save(rec);
        } catch (Exception e) {
            log.warn("bot 交互日志落库失败: channel={} eventErr={}", rec.getChannel(), e.getMessage());
        }
    }

    /** 交互日志行骨架：基础字段 + 入站截断；command/status 由调用方定（null=dispatch 内定）。 */
    private BotInteractionEntity newRecord(BotMessage msg, String command, String status) {
        BotInteractionEntity r = new BotInteractionEntity();
        r.setChannel(msg.channel());
        r.setExternalChatId(msg.externalChatId());
        r.setChatType(msg.chatType());
        r.setSenderId(msg.senderId());
        r.setCommand(command);
        r.setInputText(cut(msg.text(), 2000));
        r.setStatus(status != null ? status : BotInteractionEntity.STATUS_OK);
        return r;
    }

    private static String cut(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    // ---------------- 路由 ----------------

    /** 身份优先级（CAP-75 FR-11 四级链）：个人映射 → binding.actor → channel defaultActor → 平台默认。 */
    private String actorOf(BotMessage msg, BotBindingEntity binding) {
        java.util.Optional<String> personal = resolvePersonal(msg);
        if (personal.isPresent()) {
            return personal.get();
        }
        if (binding.getActor() != null && !binding.getActor().isBlank()) {
            return binding.getActor();
        }
        BotChannelConfigProvider cc = channelConfigs.get(msg.channel());
        if (cc != null && cc.defaultActor() != null && !cc.defaultActor().isBlank()) {
            return cc.defaultActor();
        }
        return props.getDefaultActor();
    }

    /** 个人身份映射（channel 适配器侧实现，如飞书 open_id → 第三方账号绑定）；不支持/未绑定为空。 */
    private java.util.Optional<String> resolvePersonal(BotMessage msg) {
        BotChannelConfigProvider cc = channelConfigs.get(msg.channel());
        return cc == null ? java.util.Optional.empty() : cc.resolveActor(msg.senderId());
    }

    private String route(BotMessage msg, BotBindingEntity binding, CommandParser.Command cmd) {
        if (cmd == null) {
            return ask(binding, msg.text().trim());
        }
        return switch (cmd.name()) {
            case "help" -> helpText();
            case "bindme" -> bindme(msg);
            case "whoami" -> whoami(msg, binding);
            case "unbindme" -> "解绑请登录平台：设置 → 第三方账号，删除对应飞书集成的身份码绑定即可";
            case "bind" -> bind(binding, cmd.args());
            case "session" -> startSession(binding, cmd.args());
            case "build" -> lastBuild(binding);
            case "deploy" -> lastDeploys(binding);
            case "new" -> newChat(binding);
            default -> "未知指令 /" + cmd.name() + "，发 /help 查看可用指令";
        };
    }

    /** FR-12 门禁判定：channel 支持身份映射且 sender 未绑定个人身份。 */
    private boolean rejectedByGate(BotMessage msg) {
        BotChannelConfigProvider cc = channelConfigs.get(msg.channel());
        return cc != null && cc.supportsIdentity() && resolvePersonal(msg).isEmpty();
    }

    /** FR-11 绑定入口：回 senderId 身份码 + 平台侧绑定指引（数据权威在第三方账号，bot 不写）。 */
    private String bindme(BotMessage msg) {
        return "你的身份码：" + msg.senderId()
                + "\n绑定方式：登录平台 → 设置 → 第三方账号 → 找到飞书集成 → 粘贴身份码保存"
                + "\n绑定后 /bind /session /build /deploy 将以你的平台身份执行";
    }

    /** FR-11 身份自查：回当前命中的身份级别。 */
    private String whoami(BotMessage msg, BotBindingEntity binding) {
        java.util.Optional<String> personal = resolvePersonal(msg);
        if (personal.isPresent()) {
            return "当前身份：" + personal.get() + "（个人绑定，身份码 " + msg.senderId() + "）";
        }
        if (binding.getActor() != null && !binding.getActor().isBlank()) {
            return "当前身份：" + binding.getActor() + "（群绑定身份；未绑定个人身份，发 /bindme 绑定）";
        }
        BotChannelConfigProvider cc = channelConfigs.get(msg.channel());
        String channelDefault = cc != null ? cc.defaultActor() : null;
        if (channelDefault != null && !channelDefault.isBlank()) {
            return "当前身份：" + channelDefault + "（机器人默认身份；未绑定个人身份，发 /bindme 绑定）";
        }
        return "当前身份：" + props.getDefaultActor() + "（平台默认身份；未绑定个人身份，发 /bindme 绑定）";
    }

    private String helpText() {
        return """
                可用指令：
                /help —— 本帮助
                /bindme —— 获取你的身份码（到 设置→第三方账号 绑定后，操作以你的身份执行）
                /whoami —— 查看当前身份
                /bind <项目id或名称> —— 绑定项目（无参查看当前绑定；需先绑定个人身份）
                /new —— 重开问答（丢弃当前问答上下文）
                /session <需求描述> —— 在绑定项目起开发会话（需绑定个人身份）
                /build —— 最近一条构建状态（需绑定个人身份）
                /deploy —— 最近 5 条部署状态（需绑定个人身份）
                其余文字直接当作问答（私聊直接问，群聊 @我）""";
    }

    // ---------------- 绑定 ----------------

    private BotBindingEntity findOrCreateBinding(BotMessage msg) {
        return bindingRepo.findByChannelAndExternalChatId(msg.channel(), msg.externalChatId())
                .orElseGet(() -> {
                    BotBindingEntity b = new BotBindingEntity();
                    b.setChannel(msg.channel());
                    b.setExternalChatId(msg.externalChatId());
                    b.setChatType(msg.chatType());
                    b.setOwnerSenderId(msg.senderId());
                    return bindingRepo.save(b);
                });
    }

    private String bind(BotBindingEntity binding, String args) {
        if (args == null || args.isBlank()) {
            String cur = binding.getProjectId();
            if (cur == null) {
                return "当前未绑定项目。用 /bind <项目id或名称> 绑定";
            }
            try {
                ProjectView p = projectService.get(cur);
                return "当前绑定项目：" + p.name() + "（" + p.id() + "）";
            } catch (DevMindException e) {
                return "当前绑定项目 " + cur + "（已不存在，请重新 /bind）";
            }
        }
        ProjectView p = resolveProject(args.trim());
        binding.setProjectId(p.id());
        binding.touch();
        bindingRepo.save(binding);
        return "已绑定项目：" + p.name() + "（" + p.id() + "）";
    }

    /** id 精确 → 名称精确 → 名称包含（唯一才认）。 */
    private ProjectView resolveProject(String key) {
        try {
            return projectService.get(key);
        } catch (DevMindException ignored) {
            // 落到名称匹配
        }
        List<ProjectView> all = projectService.list(null, null);
        List<ProjectView> exact = all.stream()
                .filter(p -> p.name() != null && p.name().equalsIgnoreCase(key)).toList();
        if (exact.size() == 1) {
            return exact.get(0);
        }
        List<ProjectView> fuzzy = exact.isEmpty()
                ? all.stream().filter(p -> p.name() != null && p.name().contains(key)).toList()
                : exact;
        if (fuzzy.isEmpty()) {
            throw new DevMindException(com.devmind.common.exception.ErrorCode.BAD_REQUEST,
                    "未找到项目「" + key + "」");
        }
        if (fuzzy.size() > 1) {
            StringBuilder sb = new StringBuilder("「" + key + "」匹配到多个项目，请用项目 id 绑定：");
            fuzzy.stream().limit(5).forEach(p ->
                    sb.append("\n- ").append(p.name()).append("（").append(p.id()).append("）"));
            throw new DevMindException(com.devmind.common.exception.ErrorCode.BAD_REQUEST,
                    sb.toString());
        }
        return fuzzy.get(0);
    }

    private String requireProject(BotBindingEntity binding) {
        if (binding.getProjectId() == null || binding.getProjectId().isBlank()) {
            throw new DevMindException(com.devmind.common.exception.ErrorCode.BAD_REQUEST,
                    "请先用 /bind <项目id或名称> 绑定项目");
        }
        return binding.getProjectId();
    }

    // ---------------- 问答 ----------------

    private String newChat(BotBindingEntity binding) {
        binding.setChatSessionId(null);
        binding.touch();
        bindingRepo.save(binding);
        return "已重开问答，下一条消息起新上下文";
    }

    /**
     * 问答（CAP-30 复用）：绑定无问答会话则建 MODEL 执行体问答（首条消息即本次提问）；
     * 否则续问。订阅事件流等 result 事件收口回答，超时回复提示（问答仍在平台跑）。
     */
    private String ask(BotBindingEntity binding, String text) {
        String chatId = binding.getChatSessionId();
        CompletableFuture<String> done = new CompletableFuture<>();
        StringBuilder answer = new StringBuilder();
        long[] baseline = {-1}; // 只收 baseline 之后的事件（回放里历史回合的 result 不算本轮）
        Consumer<SessionEvent> consumer = ev -> {
            if (ev.seq() <= baseline[0]) {
                return;
            }
            switch (ev.type()) {
                case "assistant" -> {
                    answer.setLength(0);
                    answer.append(ev.content() == null ? "" : ev.content());
                }
                case "result" -> {
                    boolean isError = ev.payload() != null
                            && Boolean.TRUE.equals(ev.payload().get("isError"));
                    String body = answer.length() > 0 ? answer.toString() : ev.content();
                    if (isError) {
                        done.complete("回答失败：" + (body == null || body.isBlank() ? "模型调用失败" : body));
                    } else {
                        done.complete(body == null || body.isBlank() ? "（本轮无文本回答）" : body);
                    }
                }
                case "error" -> done.complete("回答失败：" + ev.content());
                default -> { }
            }
        };
        try {
            if (chatId == null) {
                ChatView view = chatManager.create(new CreateChatRequest(
                        text, null, null, null, null, null, "MODEL", null));
                chatId = view.id();
                binding.setChatSessionId(chatId);
                binding.touch();
                bindingRepo.save(binding);
                List<SessionEvent> replay = chatManager.subscribe(chatId, consumer);
                replay.forEach(consumer); // 新建问答：回放全属本轮
            } else {
                List<SessionEvent> replay = chatManager.subscribe(chatId, consumer);
                baseline[0] = replay.stream().mapToLong(SessionEvent::seq).max().orElse(-1);
                chatManager.input(chatId, text);
            }
            String answer0 = done.get(props.getQaTimeoutSeconds(), TimeUnit.SECONDS);
            return truncate(answer0) + footer("/chats/" + chatId);
        } catch (TimeoutException e) {
            return "回答超时（>" + props.getQaTimeoutSeconds() + "s），问答仍在继续"
                    + footer("/chats/" + chatId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DevMindException(com.devmind.common.exception.ErrorCode.INTERNAL, "问答等待被中断");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new DevMindException(com.devmind.common.exception.ErrorCode.INTERNAL,
                    "问答执行失败: " + e.getCause());
        } finally {
            try {
                chatManager.unsubscribe(chatId, consumer);
            } catch (Exception ignored) {
                // 会话可能已删
            }
        }
    }

    // ---------------- 项目操作 ----------------

    private String startSession(BotBindingEntity binding, String desc) {
        String projectId = requireProject(binding);
        if (desc == null || desc.isBlank()) {
            return "用法：/session <需求描述>";
        }
        SessionView s = sessionManager.create(new CreateSessionRequest(
                null, projectId, null, null, desc, null, null, null, null));
        String preview = desc.length() > 60 ? desc.substring(0, 60) + "…" : desc;
        return "已起开发会话 " + s.id() + "\n需求：" + preview + "\n状态：" + s.status()
                + footer("/projects/" + projectId);
    }

    private String lastBuild(BotBindingEntity binding) {
        String projectId = requireProject(binding);
        List<BuildView> history = buildService.history(projectId, null);
        if (history.isEmpty()) {
            return "暂无构建记录";
        }
        BuildView b = history.get(0);
        String commit = b.commit() == null ? "-"
                : b.commit().substring(0, Math.min(7, b.commit().length()));
        return "最近构建 #" + b.id() + "：" + b.status()
                + "\n分支：" + (b.branch() == null ? "-" : b.branch()) + " " + commit
                + "\n时间：" + (b.createdAt() == null ? "-" : TIME_FMT.format(b.createdAt()))
                + (b.errorSummary() == null || b.errorSummary().isBlank() ? ""
                    : "\n错误：" + b.errorSummary());
    }

    private String lastDeploys(BotBindingEntity binding) {
        String projectId = requireProject(binding);
        PageView<DeploymentView> page = deploymentService.history(projectId, null, 1, 5);
        if (page.items().isEmpty()) {
            return "暂无部署记录";
        }
        StringBuilder sb = new StringBuilder("最近部署：");
        for (DeploymentView d : page.items()) {
            sb.append("\n- #").append(d.id()).append(" ")
                    .append(d.env() == null ? "-" : d.env()).append(" ")
                    .append(d.status()).append(" ")
                    .append(d.createdAt() == null ? "" : TIME_FMT.format(d.createdAt()));
        }
        return sb.toString();
    }

    // ---------------- 回推 ----------------

    private void reply(BotMessage msg, String text) {
        BotAdapterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            log.warn("bot 注册表未装配，回复丢弃: channel={}", msg.channel());
            return;
        }
        BotAdapter adapter = registry.get(msg.channel()).orElse(null);
        if (adapter == null) {
            log.warn("无 bot 适配器 {}，回复丢弃", msg.channel());
            return;
        }
        try {
            adapter.reply(msg, truncate(text));
        } catch (Exception e) {
            log.error("bot 回复发送失败: channel={} chat={} err={}",
                    msg.channel(), msg.externalChatId(), e.getMessage(), e);
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        int cap = props.getReplyMaxChars();
        return text.length() <= cap ? text : text.substring(0, cap) + "\n…（已截断，完整内容到平台查看）";
    }

    /** 平台链接尾注（platformBaseUrl 未配置 = 不带）。 */
    private String footer(String path) {
        String base = props.getPlatformBaseUrl();
        if (base == null || base.isBlank()) {
            return "";
        }
        return "\n平台查看：" + base.replaceAll("/+$", "") + path;
    }
}
