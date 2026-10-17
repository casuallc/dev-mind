package com.devmind.bot.service;

import com.devmind.auth.security.DevMindPrincipal;
import com.devmind.attachment.dto.AttachmentView;
import com.devmind.attachment.model.AttachmentEntity;
import com.devmind.attachment.service.AttachmentService;
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
import com.devmind.common.attachment.AttachmentContentResolver;
import com.devmind.common.dto.PageView;
import com.devmind.common.exception.DevMindException;
import com.devmind.deploy.dto.DeploymentView;
import com.devmind.deploy.service.DeploymentService;
import com.devmind.docs.DocumentService;
import com.devmind.project.DesignService;
import com.devmind.project.ProjectService;
import com.devmind.project.RequirementService;
import com.devmind.project.WorkItemService;
import com.devmind.project.dto.ProjectView;
import com.devmind.project.dto.RequirementView;
import com.devmind.project.model.RequirementEntity;
import com.devmind.project.dto.WorkItemView;
import com.devmind.release.dto.CreateReleaseRequest;
import com.devmind.release.dto.ReleaseView;
import com.devmind.release.service.ReleaseService;
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

    /** FR-12 敏感操作：支持身份映射的 channel 上要求已绑定个人身份（IMAGE=FR-17 入站截图） */
    private static final java.util.Set<String> SENSITIVE_COMMANDS =
            java.util.Set.of("bind", "session", "build", "deploy", "reqs", "req", "dev", "IMAGE",
                    "status", "stop", "wi", "design", "flow", "release");

    /** FR-22 需求状态别名（中英都收）：键=小写形态，值=平台状态码。 */
    private static final Map<String, String> FLOW_STATUS_ALIASES = Map.ofEntries(
            Map.entry("草稿", "DRAFT"), Map.entry("draft", "DRAFT"),
            Map.entry("分析中", "ANALYZING"), Map.entry("analyzing", "ANALYZING"),
            Map.entry("设计中", "DESIGNING"), Map.entry("designing", "DESIGNING"),
            Map.entry("开发中", "IN_PROGRESS"), Map.entry("in_progress", "IN_PROGRESS"),
            Map.entry("inprogress", "IN_PROGRESS"),
            Map.entry("验收", "ACCEPTANCE"), Map.entry("acceptance", "ACCEPTANCE"),
            Map.entry("完结", "DONE"), Map.entry("done", "DONE"),
            Map.entry("取消", "CANCELLED"), Map.entry("cancelled", "CANCELLED"),
            Map.entry("canceled", "CANCELLED"));

    /** FR-22 状态中文标签（回显用；未知码原样返回）。 */
    private static final Map<String, String> FLOW_STATUS_LABELS = Map.of(
            "DRAFT", "草稿", "ANALYZING", "分析中", "DESIGNING", "设计中",
            "IN_PROGRESS", "开发中", "ACCEPTANCE", "验收", "DONE", "完结", "CANCELLED", "取消");

    /** FR-22 推进到终态需二次确认（FR-21）。 */
    private static final java.util.Set<String> FLOW_CONFIRM_TARGETS =
            java.util.Set.of("DONE", "CANCELLED");

    private static final String FLOW_STATUS_MENU =
            "草稿 / 分析中 / 设计中 / 开发中 / 验收 / 完结 / 取消（英文 DRAFT/ANALYZING/DESIGNING/"
                    + "IN_PROGRESS/ACCEPTANCE/DONE/CANCELLED 同义）";

    /** FR-16 出站图片：需求描述中的本地附件引用（CAP-68 形态） */
    private static final java.util.regex.Pattern ATTACHMENT_REF =
            java.util.regex.Pattern.compile("/api/attachments/([0-9a-f]{32})/raw");
    /** FR-16 出站图片上限：最多回推 5 张、单张 ≤5MB（防刷屏/防飞书单图上限） */
    private static final int MAX_OUT_IMAGES = 5;
    private static final long MAX_OUT_IMAGE_BYTES = 5L * 1024 * 1024;

    /** 门禁拒绝时的绑定指引（同时落 REJECTED 交互日志） */
    static final String BIND_GUIDANCE = "该操作需要先绑定个人身份：给我发 /bindme 获取你的身份码，"
            + "然后登录平台 设置 → 第三方账号，在飞书集成上粘贴身份码保存";

    private final BotBindingRepository bindingRepo;
    private final BotInteractionRepository interactionRepo;
    private final ChatManagerService chatManager;
    private final SessionManagerService sessionManager;
    private final ProjectService projectService;
    private final RequirementService requirementService;
    private final WorkItemService workItemService;
    private final BuildService buildService;
    private final DeploymentService deploymentService;
    private final ReleaseService releaseService;
    private final BotSessionWatchService watchService;
    private final BotProperties props;
    private final ObjectProvider<BotAdapterRegistry> registryProvider;
    private final ObjectProvider<AttachmentContentResolver> attachmentResolverProvider;
    private final AttachmentService attachmentService;
    private final DesignService designService;
    private final DocumentService documentService;
    private final Map<String, BotChannelConfigProvider> channelConfigs = new HashMap<>();
    private final EventDedup dedup = new EventDedup();
    /** FR-17 入站截图暂存：发图 → 10 分钟内 /dev /session 消费 */
    private final PendingImageStore pendingImages = new PendingImageStore();
    /** FR-21 危险操作二次确认暂存：/stop（及 M6 后续 /flow 终态 /release）→ 确认/取消 */
    private volatile PendingConfirmStore pendingConfirms = new PendingConfirmStore();
    private final ExecutorService botExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public BotRouterService(BotBindingRepository bindingRepo,
                            BotInteractionRepository interactionRepo,
                            ChatManagerService chatManager,
                            SessionManagerService sessionManager,
                            ProjectService projectService,
                            RequirementService requirementService,
                            WorkItemService workItemService,
                            BuildService buildService,
                            DeploymentService deploymentService,
                            ReleaseService releaseService,
                            BotSessionWatchService watchService,
                            BotProperties props,
                            ObjectProvider<BotAdapterRegistry> registryProvider,
                            ObjectProvider<AttachmentContentResolver> attachmentResolverProvider,
                            AttachmentService attachmentService,
                            DesignService designService,
                            DocumentService documentService,
                            List<BotChannelConfigProvider> channelConfigProviders) {
        this.bindingRepo = bindingRepo;
        this.interactionRepo = interactionRepo;
        this.chatManager = chatManager;
        this.sessionManager = sessionManager;
        this.projectService = projectService;
        this.requirementService = requirementService;
        this.workItemService = workItemService;
        this.buildService = buildService;
        this.deploymentService = deploymentService;
        this.releaseService = releaseService;
        this.watchService = watchService;
        this.props = props;
        this.registryProvider = registryProvider;
        this.attachmentResolverProvider = attachmentResolverProvider;
        this.attachmentService = attachmentService;
        this.designService = designService;
        this.documentService = documentService;
        for (BotChannelConfigProvider p : channelConfigProviders) {
            channelConfigs.put(p.channel(), p);
        }
    }

    @PreDestroy
    void shutdown() {
        botExecutor.close();
    }

    /** 测试钩子：注入短 TTL/可控时钟的确认暂存区。 */
    void setPendingConfirms(PendingConfirmStore store) {
        this.pendingConfirms = store;
    }

    // ---------------- 入口 ----------------

    @Override
    public void onMessage(BotMessage msg) {
        if (msg == null || msg.channel() == null || msg.externalChatId() == null
                || ((msg.text() == null || msg.text().isBlank()) && msg.imageKey() == null)) {
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
        RouteReply routeResult = null;
        try {
            BotBindingEntity binding = findOrCreateBinding(msg);
            String actor = actorOf(msg, binding);
            rec.setActor(actor);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            new DevMindPrincipal(actor, "USER"), null, List.of()));
            CommandParser.Command cmd = msg.imageKey() != null ? null : CommandParser.parse(msg.text());
            String command = msg.imageKey() != null ? "IMAGE" : (cmd == null ? "QA" : cmd.name());
            if (cmd == null && msg.imageKey() == null) {
                // FR-21：裸文本「确认/取消」命中暂存时，交互行记原指令名（确认）或 cancel
                String pendingName = pendingConfirms.peekName(pendingKey(msg), msg.text().trim());
                if (pendingName != null) {
                    command = pendingName;
                }
            }
            rec.setCommand(command);
            boolean gated = msg.imageKey() != null
                    || (cmd != null && SENSITIVE_COMMANDS.contains(cmd.name()));
            if (gated && rejectedByGate(msg)) {
                rec.setStatus(BotInteractionEntity.STATUS_REJECTED);
                replyText = BIND_GUIDANCE;
            } else {
                routeResult = route(msg, binding, cmd);
                replyText = routeResult.text();
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
        String replyError = reply(msg, replyText);
        if (replyError != null) {
            // 回推失败 = 用户视角的失败（群里什么都没收到），行状态必须改 FAILED，不能只记日志
            rec.setStatus(BotInteractionEntity.STATUS_FAILED);
            rec.setError(cut("回复发送失败: " + replyError, 512));
            saveInteraction(rec, replyText);
        } else if (routeResult != null && !routeResult.images().isEmpty()) {
            // FR-16：文本回推成功后逐张发图（失败补文字提示，不翻 FAILED——主回答已送达）
            sendImages(msg, routeResult.images());
        }
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
        r.setInputText(msg.imageKey() != null
                ? cut("[图片] " + msg.imageKey(), 2000) : cut(msg.text(), 2000));
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

    /** 路由产出：回复文本 + 待回推的出站图片（FR-16；仅 /req 详情产图）。 */
    private record RouteReply(String text, List<OutImage> images) {
        static RouteReply text(String text) {
            return new RouteReply(text, List.of());
        }
    }

    /** 出站图片（已解析好字节，适配器直接上传发送）。 */
    private record OutImage(String fileName, String contentType, byte[] bytes) {
    }

    private RouteReply route(BotMessage msg, BotBindingEntity binding, CommandParser.Command cmd) {
        if (msg.imageKey() != null) {
            return RouteReply.text(receiveImage(msg));
        }
        if (cmd == null) {
            RouteReply confirmed = confirmReply(msg, binding);
            if (confirmed != null) {
                return confirmed;
            }
            return RouteReply.text(ask(binding, msg.text().trim()));
        }
        return switch (cmd.name()) {
            case "help" -> RouteReply.text(helpText());
            case "bindme" -> RouteReply.text(bindme(msg));
            case "whoami" -> RouteReply.text(whoami(msg, binding));
            case "unbindme" -> RouteReply.text(
                    "解绑请登录平台：设置 → 第三方账号，删除对应飞书集成的身份码绑定即可");
            case "bind" -> RouteReply.text(bind(binding, cmd.args()));
            case "session" -> RouteReply.text(startSession(msg, binding, cmd.args()));
            case "reqs" -> RouteReply.text(listRequirements(binding));
            case "req" -> requirementDetail(binding, cmd.args());
            case "dev" -> RouteReply.text(devSession(msg, binding, cmd.args()));
            case "status" -> RouteReply.text(sessionStatus(binding));
            case "stop" -> RouteReply.text(stopWithConfirm(msg, binding, cmd.args()));
            case "wi" -> RouteReply.text(wiList(binding, cmd.args()));
            case "design" -> RouteReply.text(designDetail(binding, cmd.args()));
            case "flow" -> RouteReply.text(flow(msg, binding, cmd.args()));
            case "release" -> RouteReply.text(release(msg, binding, cmd.args()));
            case "build" -> RouteReply.text(lastBuild(binding));
            case "deploy" -> RouteReply.text(lastDeploys(binding));
            case "new" -> RouteReply.text(newChat(binding));
            default -> RouteReply.text("未知指令 /" + cmd.name() + "，发 /help 查看可用指令");
        };
    }

    /**
     * FR-21 二次确认应答：裸文本「确认」消费暂存并执行原操作，「取消」丢弃；
     * 无暂存（或已过期）返回 null 落 QA 不特殊处理。暂存 key 含 senderId，
     * 他人无法替你确认。
     */
    private RouteReply confirmReply(BotMessage msg, BotBindingEntity binding) {
        String text = msg.text().trim();
        if ("确认".equals(text)) {
            PendingConfirmStore.Entry e = pendingConfirms.poll(pendingKey(msg));
            return e == null ? null : RouteReply.text(executeConfirmed(binding, e));
        }
        if ("取消".equals(text)) {
            return pendingConfirms.removeFresh(pendingKey(msg))
                    ? RouteReply.text("已取消，操作未执行") : null;
        }
        return null;
    }

    /** 确认通过后的原操作执行（各指令重校验当前状态，不盲信暂存时的快照）。 */
    private String executeConfirmed(BotBindingEntity binding, PendingConfirmStore.Entry e) {
        return switch (e.name()) {
            case "stop" -> stopConfirmed(binding, e.args());
            case "flow" -> flowConfirmed(binding, e.args());
            case "release" -> releaseConfirmed(binding, e.args());
            default -> "暂存的操作已失效，请重新发起（/" + e.name() + "）";
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
                /reqs —— 进行中的需求列表（需绑定个人身份）
                /req <编号> —— 需求详情（编号如 REQ-3；需绑定个人身份）
                /wi <编号> —— 需求下工作单元列表（需绑定个人身份）
                /design <编号> —— 需求下方案列表 + 最新方案正文（需绑定个人身份）
                /flow <编号> [目标状态] [force] —— 查看/推进需求状态（完结/取消需二次确认；需绑定个人身份）
                /dev <编号> [补充说明] —— 以需求起开发会话，挂研发主线（需绑定个人身份）
                /session <需求描述> —— 起自由文本开发会话（需绑定个人身份）
                （/dev /session 起的会话结束时，进展会自动推回本聊天）
                /build —— 最近一条构建状态（需绑定个人身份）
                /release [版本号] [构建id] —— 查看最近发版 / 发起发版（需二次确认；需绑定个人身份）
                /deploy —— 最近 5 条部署状态（需绑定个人身份）
                /status —— 最近 5 条开发会话状态（需绑定个人身份）
                /stop [会话id] —— 停止会话（危险操作需二次确认；无参停最新运行中的；需绑定个人身份）
                直接发截图给我 —— 10 分钟内发 /dev 或 /session 起会话自动带上（需绑定个人身份）
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
     *
     * <p>续问撞 NOT_FOUND（绑定问答在平台被删，或群内换人导致归属校验不通过）时自愈：
     * 丢弃失效引用原地重建新问答，本轮消息不丢。</p>
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
            if (chatId != null) {
                try {
                    List<SessionEvent> replay = chatManager.subscribe(chatId, consumer);
                    baseline[0] = replay.stream().mapToLong(SessionEvent::seq).max().orElse(-1);
                    chatManager.input(chatId, text);
                } catch (DevMindException e) {
                    if (e.getErrorCode() != com.devmind.common.exception.ErrorCode.NOT_FOUND) {
                        throw e;
                    }
                    log.info("bot 绑定问答已失效（{}），丢弃引用重建: chat={}",
                            e.getMessage(), chatId);
                    try {
                        chatManager.unsubscribe(chatId, consumer); // subscribe 成功而 input 撞删的竞态
                    } catch (Exception ignored) {
                        // 问答已删，无订阅可清
                    }
                    binding.setChatSessionId(null);
                    chatId = null;
                }
            }
            if (chatId == null) {
                ChatView view = chatManager.create(new CreateChatRequest(
                        text, null, null, null, null, null, "MODEL", null));
                chatId = view.id();
                binding.setChatSessionId(chatId);
                binding.touch();
                bindingRepo.save(binding);
                List<SessionEvent> replay = chatManager.subscribe(chatId, consumer);
                replay.forEach(consumer); // 新建问答：回放全属本轮
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

    private String startSession(BotMessage msg, BotBindingEntity binding, String desc) {
        String projectId = requireProject(binding);
        if (desc == null || desc.isBlank()) {
            return "用法：/session <需求描述>";
        }
        List<String> attachmentIds = pendingImages.drain(pendingKey(msg));
        SessionView s = sessionManager.create(new CreateSessionRequest(
                null, null, projectId, null, null, desc, null, null, null, null,
                null, null, null, null, null,
                attachmentIds.isEmpty() ? null : attachmentIds));
        // FR-20：订阅终态推送——会话结束（含失败）时把进展推回这个聊天
        watchService.register(s.id(), msg.channel(), msg.externalChatId(), desc);
        String preview = desc.length() > 60 ? desc.substring(0, 60) + "…" : desc;
        return "已起开发会话 " + s.id() + "\n需求：" + preview + "\n状态：" + s.status()
                + (attachmentIds.isEmpty() ? "" : "\n（已附 " + attachmentIds.size() + " 张截图）")
                + footer("/projects/" + projectId);
    }

    // ---------------- 研发主线（M4 FR-15） ----------------

    /** /reqs：未完结需求（OPEN 伪状态），seq 倒序前 10 条。 */
    private String listRequirements(BotBindingEntity binding) {
        String projectId = requireProject(binding);
        PageView<RequirementView> page = requirementService.list(
                projectId, "OPEN", null, null, null, "seq", "desc", 0, 10);
        if (page.items().isEmpty()) {
            return "暂无进行中的需求" + footer("/projects/" + projectId);
        }
        StringBuilder sb = new StringBuilder("进行中的需求（前 " + page.items().size()
                + " 条，共 " + page.total() + "）：");
        for (RequirementView r : page.items()) {
            sb.append("\n- ").append(r.code()).append(" [").append(r.status()).append("] ")
                    .append(r.title());
        }
        sb.append("\n发 /req <编号> 看详情，/dev <编号> 以该需求起开发会话");
        return sb.toString();
    }

    /** /req <编号>：需求详情（描述截断 500）+ 工作单元前 5 条 + 描述图片逐张回推（FR-16）。 */
    private RouteReply requirementDetail(BotBindingEntity binding, String args) {
        String projectId = requireProject(binding);
        if (args == null || args.isBlank()) {
            return RouteReply.text("用法：/req <需求编号>（编号见 /reqs，如 REQ-3）");
        }
        RequirementView r = resolveRequirement(projectId, args.trim());
        StringBuilder sb = new StringBuilder();
        sb.append(r.code()).append(" ").append(r.title())
                .append("\n状态：").append(r.status())
                .append(r.type() == null ? "" : " ｜ 类型：" + r.type())
                .append(r.assignee() == null ? "" : " ｜ 负责人：" + r.assignee());
        if (r.description() != null && !r.description().isBlank()) {
            String d = r.description().strip();
            sb.append("\n描述：").append(d.length() > 500 ? d.substring(0, 500) + "…" : d);
        }
        List<WorkItemView> wis = workItemService.list(projectId, r.id());
        if (!wis.isEmpty()) {
            sb.append("\n工作单元（").append(wis.size()).append("）：");
            wis.stream().limit(5).forEach(w -> sb.append("\n- ").append(w.code())
                    .append(" [").append(w.status()).append("] ").append(w.title()));
            if (wis.size() > 5) {
                sb.append("\n  …");
            }
        }
        sb.append("\n发 /dev ").append(r.code()).append(" 以该需求起开发会话");
        List<OutImage> images = collectOutImages(r.description());
        if (!images.isEmpty()) {
            sb.append("\n（附 ").append(images.size()).append(" 张需求截图，见下）");
        }
        return new RouteReply(sb.toString(), images);
    }

    /**
     * FR-16：解析描述中的本地附件引用（去重保序），经 AttachmentContentResolver 拉字节。
     * 仅 image/* 且 ≤5MB，最多 5 张；attachment 模块未装配/单张失效静默跳过（文本已带引用路径，
     * 平台链接可兜底查看）。
     */
    private List<OutImage> collectOutImages(String description) {
        if (description == null || description.isBlank()) {
            return List.of();
        }
        AttachmentContentResolver resolver = attachmentResolverProvider.getIfAvailable();
        if (resolver == null) {
            return List.of();
        }
        List<String> ids = new java.util.ArrayList<>();
        var m = ATTACHMENT_REF.matcher(description);
        while (m.find() && ids.size() < MAX_OUT_IMAGES) {
            if (!ids.contains(m.group(1))) {
                ids.add(m.group(1));
            }
        }
        List<OutImage> out = new java.util.ArrayList<>();
        for (String id : ids) {
            try {
                var resolved = resolver.resolve(id);
                if (resolved.isEmpty()) {
                    continue;
                }
                var a = resolved.get();
                if (a.contentType() == null || !a.contentType().startsWith("image/")
                        || a.bytes() == null || a.bytes().length > MAX_OUT_IMAGE_BYTES) {
                    continue;
                }
                String name = a.originalName() == null || a.originalName().isBlank()
                        ? "requirement-" + id.substring(0, 8) + ".png" : a.originalName();
                out.add(new OutImage(name, a.contentType(), a.bytes()));
            } catch (Exception e) {
                log.warn("bot 出站图片解析失败，跳过: attachment={} err={}", id, e.getMessage());
            }
        }
        return out;
    }

    /**
     * /dev <编号> [补充说明]：以需求起开发会话——taskSpec = 需求标题+描述+补充，
     * 挂 requirementId 进研发主线（平台需求页可追溯其下会话）。
     */
    private String devSession(BotMessage msg, BotBindingEntity binding, String args) {
        String projectId = requireProject(binding);
        if (args == null || args.isBlank()) {
            return "用法：/dev <需求编号> [补充说明]（编号见 /reqs）";
        }
        String[] parts = args.trim().split("\\s+", 2);
        RequirementView r = resolveRequirement(projectId, parts[0]);
        String extra = parts.length > 1 ? parts[1].trim() : "";
        StringBuilder spec = new StringBuilder("【").append(r.code()).append("】").append(r.title());
        if (r.description() != null && !r.description().isBlank()) {
            spec.append("\n\n").append(r.description().strip());
        }
        if (!extra.isEmpty()) {
            spec.append("\n\n补充说明：").append(extra);
        }
        List<String> attachmentIds = pendingImages.drain(pendingKey(msg));
        SessionView s = sessionManager.create(new CreateSessionRequest(
                null, null, projectId, null, r.id(), spec.toString(), null, null, null, null,
                null, null, null, null, null,
                attachmentIds.isEmpty() ? null : attachmentIds));
        // FR-20：订阅终态推送（taskSpec 用需求标题+描述的首段，推送文案够读）
        watchService.register(s.id(), msg.channel(), msg.externalChatId(), spec.toString());
        return "已起开发会话 " + s.id() + "（需求 " + r.code() + "）\n标题：" + r.title()
                + "\n状态：" + s.status()
                + (attachmentIds.isEmpty() ? "" : "\n（已附 " + attachmentIds.size() + " 张截图）")
                + footer("/projects/" + projectId);
    }

    // ---------------- 会话控制（M5 FR-18） ----------------

    /** /status：绑定项目最近 5 条会话（createdAt 倒序），RUNNING 附摘要。 */
    private String sessionStatus(BotBindingEntity binding) {
        String projectId = requireProject(binding);
        List<SessionView> all = sessionManager.list(null, projectId, null, null);
        if (all.isEmpty()) {
            return "暂无开发会话" + footer("/projects/" + projectId);
        }
        StringBuilder sb = new StringBuilder("最近会话（前 " + Math.min(5, all.size())
                + " 条，共 " + all.size() + "）：");
        for (SessionView s : all.stream().limit(5).toList()) {
            sb.append("\n- ").append(shortId(s.id())).append(" [").append(s.status()).append("] ")
                    .append(previewOf(s.taskSpec(), 40));
            if (s.createdAt() != null) {
                sb.append(" ｜ ").append(TIME_FMT.format(s.createdAt()));
            }
            if ("RUNNING".equals(s.status()) && s.summary() != null && !s.summary().isBlank()) {
                sb.append("\n  进展：").append(previewOf(s.summary(), 60));
            }
        }
        sb.append("\n发 /stop <会话id前几位> 停止会话");
        return sb.toString();
    }

    /**
     * /stop [id|唯一前缀]（FR-21 危险操作）：先解析目标并暂存，用户回「确认」才真正 kill。
     * 终态会话直接短路回复（无副作用，不需确认）；解析失败（空态/不存在/多匹配）照样抛错。
     */
    private String stopWithConfirm(BotMessage msg, BotBindingEntity binding, String args) {
        String projectId = requireProject(binding);
        SessionView target = resolveStopTarget(projectId, args);
        if (isTerminal(target)) {
            return "会话 " + shortId(target.id()) + " 已是 " + target.status() + "，无需停止";
        }
        String desc = "停止会话 " + shortId(target.id())
                + "（任务：" + previewOf(target.taskSpec(), 40) + "）";
        pendingConfirms.put(pendingKey(msg), "stop", args, desc);
        return "⚠️ 危险操作确认：将" + desc + "\n回复 确认 执行，回复 取消 放弃（10 分钟内有效）";
    }

    /** 确认通过后执行：重解析目标（暂存期间状态可能已变化），存活才 kill（requireRuntime 兜底）。 */
    private String stopConfirmed(BotBindingEntity binding, String args) {
        String projectId = requireProject(binding);
        SessionView target = resolveStopTarget(projectId, args);
        if (isTerminal(target)) {
            return "会话 " + shortId(target.id()) + " 已是 " + target.status() + "，无需停止";
        }
        SessionView killed = sessionManager.kill(target.id());
        // FR-20：用户已在场主动停的会话不再推终态；平台侧 kill/自然结束照样推（onExit 都广播）
        watchService.remove(killed.id());
        return "已停止会话 " + shortId(killed.id()) + "\n任务：" + previewOf(killed.taskSpec(), 40)
                + "\n状态：" + killed.status() + footer("/projects/" + projectId);
    }

    private static boolean isTerminal(SessionView s) {
        return !"RUNNING".equals(s.status()) && !"QUEUED".equals(s.status());
    }

    /** /stop 目标解析：无参取最新 RUNNING；有参按 id 精确/唯一前缀匹配。 */
    private SessionView resolveStopTarget(String projectId, String args) {
        if (args == null || args.isBlank()) {
            return sessionManager.list("RUNNING", projectId, null, null).stream().findFirst()
                    .orElseThrow(() -> new DevMindException(
                            com.devmind.common.exception.ErrorCode.BAD_REQUEST,
                            "当前没有运行中的会话"));
        }
        String key = args.trim();
        List<SessionView> hits = sessionManager.list(null, projectId, null, null).stream()
                .filter(s -> s.id().equals(key) || s.id().startsWith(key)).toList();
        if (hits.isEmpty()) {
            throw new DevMindException(com.devmind.common.exception.ErrorCode.NOT_FOUND,
                    "会话不存在: " + key);
        }
        if (hits.size() > 1) {
            StringBuilder sb = new StringBuilder("「" + key + "」匹配到多个会话，请补全 id：");
            hits.stream().limit(5).forEach(s ->
                    sb.append("\n- ").append(shortId(s.id())).append(" [").append(s.status())
                            .append("] ").append(previewOf(s.taskSpec(), 30)));
            throw new DevMindException(com.devmind.common.exception.ErrorCode.BAD_REQUEST,
                    sb.toString());
        }
        return hits.get(0);
    }

    // ---------------- 主线查看（M5 FR-19） ----------------

    /** /wi <编号>：需求下工作单元全量列表（前 20 条：编号+状态+类型+标题）。 */
    private String wiList(BotBindingEntity binding, String args) {
        String projectId = requireProject(binding);
        if (args == null || args.isBlank()) {
            return "用法：/wi <需求编号>（编号见 /reqs，如 REQ-3）";
        }
        RequirementView r = resolveRequirement(projectId, args.trim());
        List<WorkItemView> wis = workItemService.list(projectId, r.id());
        if (wis.isEmpty()) {
            return r.code() + " 下暂无工作单元" + footer("/projects/" + projectId);
        }
        StringBuilder sb = new StringBuilder(r.code() + " 工作单元（" + wis.size() + " 个）：");
        wis.stream().limit(20).forEach(w -> sb.append("\n- ").append(w.code())
                .append(" [").append(w.status()).append("] ")
                .append(w.type() == null ? "" : w.type() + " ").append(w.title()));
        if (wis.size() > 20) {
            sb.append("\n  …（其余 ").append(wis.size() - 20).append(" 个到平台查看）");
        }
        return sb.toString();
    }

    /** /design <编号>：需求下方案列表 + 最新方案正文截断 1500 字。 */
    private String designDetail(BotBindingEntity binding, String args) {
        String projectId = requireProject(binding);
        if (args == null || args.isBlank()) {
            return "用法：/design <需求编号>（编号见 /reqs，如 REQ-3）";
        }
        RequirementView r = resolveRequirement(projectId, args.trim());
        List<com.devmind.project.dto.DesignView> designs = designService.list(projectId, r.id());
        if (designs.isEmpty()) {
            return r.code() + " 下暂无方案" + footer("/projects/" + projectId);
        }
        StringBuilder sb = new StringBuilder(r.code() + " 方案（" + designs.size() + " 个）：");
        for (com.devmind.project.dto.DesignView d : designs) {
            sb.append("\n- v").append(d.version()).append(" [").append(d.status()).append("]");
            if (d.updatedAt() != null) {
                sb.append(" ").append(TIME_FMT.format(d.updatedAt()));
            }
        }
        com.devmind.project.dto.DesignView latest = designs.stream()
                .max(java.util.Comparator.comparing(d ->
                        d.updatedAt() != null ? d.updatedAt() : java.time.Instant.EPOCH))
                .orElse(designs.get(designs.size() - 1));
        if (latest.docId() != null) {
            String content = documentService.get(latest.docId(), null).contentMd();
            if (content != null && !content.isBlank()) {
                String body = content.strip();
                sb.append("\n\n最新方案 v").append(latest.version()).append(" 正文：\n")
                        .append(body.length() > 1500 ? body.substring(0, 1500) + "…" : body);
            }
        }
        return sb.append(footer("/projects/" + projectId)).toString();
    }

    // ---------------- 发版触发（M6 FR-23） ----------------

    /**
     * /release [版本号] [构建id]：无参回最近 5 条发版记录；有参走发版——
     * 预检（构建产物解析；无构建 id 时取最近一条带产物的 SUCCESS 构建）→ FR-21 确认 →
     * create + execute（PLANNED→RUNNING 异步，后续进展由通知中心/平台发版页跟踪）。
     *
     * <p>发版配置缺失、版本幂等等 create 侧校验失败在确认后透传（不在预检期试跑 create，
     * 否则取消会留下一条 PLANNED 记录）。</p>
     */
    private String release(BotMessage msg, BotBindingEntity binding, String args) {
        String projectId = requireProject(binding);
        if (args == null || args.isBlank()) {
            return releaseHistory(projectId);
        }
        String[] parts = args.trim().split("\\s+");
        String version = parts[0].trim();
        Long buildId = null;
        if (parts.length > 1) {
            try {
                buildId = Long.parseLong(parts[1].trim());
            } catch (NumberFormatException e) {
                throw new DevMindException(com.devmind.common.exception.ErrorCode.BAD_REQUEST,
                        "构建 id 必须是数字：" + parts[1]);
            }
        }
        BuildView build = buildId != null
                ? requireArtifactBuild(projectId, buildId) : latestArtifactBuild(projectId);
        String desc = "发版 " + version + "（构建 #" + build.id() + "，产物 "
                + previewOf(build.artifactRef(), 40) + "）";
        pendingConfirms.put(pendingKey(msg), "release", version + " " + build.id(), desc);
        return "⚠️ 危险操作确认：将" + desc + "\n回复 确认 执行，回复 取消 放弃（10 分钟内有效）";
    }

    /** 确认通过后执行：重解析构建（暂存期间可能已删除/换产物）→ create → execute。 */
    private String releaseConfirmed(BotBindingEntity binding, String args) {
        String projectId = requireProject(binding);
        String[] parts = args.trim().split("\\s+");
        String version = parts[0];
        Long buildId = Long.parseLong(parts[1]);
        requireArtifactBuild(projectId, buildId);
        // 配置缺失/版本幂等等 create 侧校验失败统一抛给 dispatch（「操作失败：」+ 原文案透传）
        ReleaseView created = releaseService.create(new CreateReleaseRequest(
                projectId, null, buildId, version, null, null, null));
        ReleaseView running = releaseService.execute(created.id());
        return "已发起发版 #" + running.id() + " " + running.version()
                + "\n状态：" + running.status() + "（异步执行中）"
                + "\n产物：" + (running.artifactRef() == null ? "-" : running.artifactRef())
                + footer("/projects/" + projectId);
    }

    /** 最近 5 条发版记录（createdAt 倒序）。 */
    private String releaseHistory(String projectId) {
        List<ReleaseView> history = releaseService.history(projectId, null);
        if (history.isEmpty()) {
            return "暂无发版记录" + footer("/projects/" + projectId);
        }
        StringBuilder sb = new StringBuilder("最近发版（前 " + Math.min(5, history.size())
                + " 条，共 " + history.size() + "）：");
        for (ReleaseView r : history.stream().limit(5).toList()) {
            sb.append("\n- #").append(r.id()).append(" ").append(r.version())
                    .append(" [").append(r.status()).append("] ");
            if (r.createdAt() != null) {
                sb.append(TIME_FMT.format(r.createdAt())).append(" ");
            }
            if (r.errorSummary() != null && !r.errorSummary().isBlank()) {
                sb.append("\n  错误：").append(previewOf(r.errorSummary(), 60));
            }
        }
        sb.append("\n发版：/release <版本号> [构建id]（缺省构建取最近一条带产物的成功构建）");
        return sb.toString();
    }

    /** 最近一条带产物的 SUCCESS 构建；没有明确告知（不静默退化成任意构建）。 */
    private BuildView latestArtifactBuild(String projectId) {
        return buildService.history(projectId, "SUCCESS").stream()
                .filter(b -> b.artifactRef() != null && !b.artifactRef().isBlank())
                .findFirst()
                .orElseThrow(() -> new DevMindException(
                        com.devmind.common.exception.ErrorCode.BAD_REQUEST,
                        "没有带产物的成功构建，无法发版（先跑一次成功构建，或用 /release <版本号> <构建id> 指定）"));
    }

    /** 指定构建 id：必须存在、属于本项目且已登记产物（确认期复核走这里，不依赖预检用的是哪条查询）。 */
    private BuildView requireArtifactBuild(String projectId, Long buildId) {
        BuildView b = buildService.get(buildId);
        if (!projectId.equals(b.projectId())) {
            throw new DevMindException(com.devmind.common.exception.ErrorCode.NOT_FOUND,
                    "构建 #" + buildId + " 不属于当前绑定项目");
        }
        if (b.artifactRef() == null || b.artifactRef().isBlank()) {
            throw new DevMindException(com.devmind.common.exception.ErrorCode.BAD_REQUEST,
                    "构建 #" + buildId + " 未登记产物，无法发版");
        }
        return b;
    }

    // ---------------- 需求状态推进（M6 FR-22） ----------------

    /**
     * /flow &lt;编号&gt; [状态] [force]：无状态回当前状态 + 可选目标手册；
     * 推进到 DONE/CANCELLED 走 FR-21 二次确认；DONE 撞未完结工作单元时给 force 提示。
     */
    private String flow(BotMessage msg, BotBindingEntity binding, String args) {
        String projectId = requireProject(binding);
        if (args == null || args.isBlank()) {
            return "用法：/flow <需求编号> [目标状态] [force]\n可选状态：" + FLOW_STATUS_MENU;
        }
        String[] parts = args.trim().split("\\s+");
        RequirementView r = resolveRequirement(projectId, parts[0]);
        if (parts.length == 1) {
            return r.code() + " " + r.title()
                    + "\n当前状态：" + statusLabel(r.status())
                    + "\n可推进到：" + FLOW_STATUS_MENU
                    + "\n用法：/flow " + r.code() + " <目标状态> [force]";
        }
        String target = normalizeFlowStatus(parts[1]);
        boolean force = parts.length > 2 && "force".equalsIgnoreCase(parts[2]);
        if (target.equals(r.status())) {
            return r.code() + " 已是 " + statusLabel(target) + "，无需推进";
        }
        if (RequirementEntity.STATUS_DONE.equals(target) && !force) {
            long active = activeWorkItemCount(projectId, r.id());
            if (active > 0) {
                // 提前把平台的 409 语义摊开说，别让用户白确认一轮才被拒
                return r.code() + " 还有 " + active + " 个未完结工作单元，直接完结会被平台拒绝。\n"
                        + "如确认强制完结，请发送：/flow " + r.code() + " 完结 force";
            }
        }
        if (FLOW_CONFIRM_TARGETS.contains(target)) {
            String desc = "把 " + r.code() + "（" + r.title() + "）从 " + statusLabel(r.status())
                    + " 推进到 " + statusLabel(target) + (force ? "（强制）" : "");
            pendingConfirms.put(pendingKey(msg), "flow", args.trim(), desc);
            return "⚠️ 危险操作确认：将" + desc + "\n回复 确认 执行，回复 取消 放弃（10 分钟内有效）";
        }
        return applyFlow(projectId, r, target, force);
    }

    /** 确认通过后的 /flow 重放：状态可能已变，重新校验后落库（撞 409 给 force 提示）。 */
    private String flowConfirmed(BotBindingEntity binding, String args) {
        String projectId = requireProject(binding);
        String[] parts = args.trim().split("\\s+");
        RequirementView r = resolveRequirement(projectId, parts[0]);
        String target = normalizeFlowStatus(parts[1]);
        boolean force = parts.length > 2 && "force".equalsIgnoreCase(parts[2]);
        if (target.equals(r.status())) {
            return r.code() + " 已是 " + statusLabel(target) + "，无需推进";
        }
        return applyFlow(projectId, r, target, force);
    }

    /** 落库（确认前后同路径）：撞 409 且目标是 DONE 时附 force 提示，其余原样抛。 */
    private String applyFlow(String projectId, RequirementView r, String target, boolean force) {
        try {
            RequirementView updated = requirementService.updateStatus(projectId, r.id(), target, force);
            return r.code() + " 状态已更新：" + statusLabel(updated.status())
                    + footer("/projects/" + projectId);
        } catch (DevMindException e) {
            if (e.getErrorCode() == com.devmind.common.exception.ErrorCode.CONFLICT
                    && RequirementEntity.STATUS_DONE.equals(target) && !force) {
                return r.code() + " 推进失败：" + e.getMessage()
                        + "\n如确认强制完结，请发送：/flow " + r.code() + " 完结 force";
            }
            throw e;
        }
    }

    /** 未完结工作单元数（DONE 前置检查的本地预览，与 RequirementService 同口径）。 */
    private long activeWorkItemCount(String projectId, String requirementId) {
        return workItemService.list(projectId, requirementId).stream()
                .filter(w -> !"DONE".equals(w.status()) && !"CANCELLED".equals(w.status()))
                .count();
    }

    /** 状态别名归一（中英都收，大小写不敏感）；不识别报错并附可选清单。 */
    private static String normalizeFlowStatus(String raw) {
        String key = raw.trim().toLowerCase(java.util.Locale.ROOT);
        String code = FLOW_STATUS_ALIASES.get(key);
        if (code == null) {
            throw new DevMindException(com.devmind.common.exception.ErrorCode.BAD_REQUEST,
                    "不认识的状态「" + raw + "」，可选：" + FLOW_STATUS_MENU);
        }
        return code;
    }

    private static String statusLabel(String code) {
        if (code == null) {
            return "-";
        }
        String label = FLOW_STATUS_LABELS.get(code);
        return label == null ? code : label + "（" + code + "）";
    }

    private static String shortId(String id) {
        return id == null ? "-" : id.substring(0, Math.min(8, id.length()));
    }

    private static String previewOf(String text, int max) {
        if (text == null || text.isBlank()) {
            return "-";
        }
        String oneLine = text.strip().replace('\n', ' ');
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }

    /** 需求编号解析：REQ-3 / req-3 / 3 按项目内 seq 查，否则按原始 id 查。 */
    private RequirementView resolveRequirement(String projectId, String key) {
        // 前缀整体可选：REQ-? 只让短横可选，纯数字 3 会落进 id 直查分支（NOT_FOUND/NPE）
        var m = java.util.regex.Pattern.compile("(?i)(?:REQ-?)?(\\d+)").matcher(key);
        if (m.matches()) {
            long seq = Long.parseLong(m.group(1));
            return requirementService.findByProjectSeq(projectId, seq)
                    .map(e -> requirementService.get(projectId, e.getId()))
                    .orElseThrow(() -> new DevMindException(
                            com.devmind.common.exception.ErrorCode.NOT_FOUND,
                            "需求不存在: " + key));
        }
        return requirementService.get(projectId, key);
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

    // ---------------- 入站图片（M5 FR-17） ----------------

    /**
     * 收图：botExecutor 里下载（不占事件回调 3s 窗口）→ 魔数嗅探 contentType →
     * 存平台附件（SHARED，全项目可引）→ 暂存 10 分钟，等下一条 /dev /session 消费。
     */
    private String receiveImage(BotMessage msg) {
        BotAdapter adapter = adapterOf(msg.channel());
        if (adapter == null) {
            return "当前平台不可用，请稍后再试";
        }
        byte[] bytes;
        try {
            bytes = adapter.downloadImage(msg.imageKey());
        } catch (Exception e) {
            throw new DevMindException(com.devmind.common.exception.ErrorCode.INTERNAL,
                    "图片下载失败：" + e.getMessage());
        }
        if (bytes == null) {
            return "当前平台不支持发送图片给机器人";
        }
        String contentType = sniffImageType(bytes);
        if (contentType == null) {
            return "收到的不是可识别的图片（支持 png/jpeg/gif/webp），请重发截图";
        }
        String name = "bot-" + msg.senderId() + "-" + System.currentTimeMillis()
                + switch (contentType) {
                    case "image/jpeg" -> ".jpg";
                    case "image/gif" -> ".gif";
                    case "image/webp" -> ".webp";
                    default -> ".png";
                };
        AttachmentView view = attachmentService.upload(
                new InMemoryMultipartFile(name, contentType, bytes),
                AttachmentEntity.SCOPE_SHARED, "bot 用户 " + msg.senderId() + " 发送的截图");
        int n = pendingImages.add(pendingKey(msg), view.attachmentId());
        return "已收到截图（暂存 " + n + " 张，10 分钟内有效）\n"
                + "发 /dev <需求编号> 或 /session <描述> 起开发会话会自动带上";
    }

    private String pendingKey(BotMessage msg) {
        return msg.channel() + "|" + msg.externalChatId() + "|" + msg.senderId();
    }

    /** 图片魔数嗅探（不信任平台声明的 contentType）；不识别返回 null。 */
    static String sniffImageType(byte[] b) {
        if (b == null || b.length < 4) {
            return null;
        }
        if ((b[0] & 0xFF) == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47) {
            return "image/png";
        }
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b[0] == 0x47 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x38) {
            return "image/gif";
        }
        if (b.length >= 12 && b[0] == 0x52 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x46
                && b[8] == 0x57 && b[9] == 0x45 && b[10] == 0x42 && b[11] == 0x50) {
            return "image/webp";
        }
        return null;
    }

    /** 内存字节包装的 MultipartFile（AttachmentService 只认 MultipartFile；MockMultipartFile 是 test 包不可用）。 */
    private record InMemoryMultipartFile(String name, String contentType, byte[] bytes)
            implements org.springframework.web.multipart.MultipartFile {
        @Override
        public String getName() {
            return "image";
        }

        @Override
        public String getOriginalFilename() {
            return name;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return bytes.length == 0;
        }

        @Override
        public long getSize() {
            return bytes.length;
        }

        @Override
        public byte[] getBytes() {
            return bytes;
        }

        @Override
        public java.io.InputStream getInputStream() {
            return new java.io.ByteArrayInputStream(bytes);
        }

        @Override
        public void transferTo(java.io.File dest) throws java.io.IOException {
            java.nio.file.Files.write(dest.toPath(), bytes);
        }
    }

    /**
     * FR-17 暂存区：key=channel|chat|sender，TTL 10 分钟，单人最多攒 5 张（超出挤掉最旧）。
     * 纯内存（重启即清，截图时效短、丢了重发即可）；惰性过期（add/drain 时才清理）。
     */
    private static final class PendingImageStore {
        private static final long TTL_MS = 10 * 60 * 1000;
        private static final int MAX_PER_USER = 5;
        private final Map<String, java.util.Deque<Entry>> store =
                new java.util.concurrent.ConcurrentHashMap<>();

        private record Entry(String attachmentId, long expireAt) {
        }

        synchronized int add(String key, String attachmentId) {
            java.util.Deque<Entry> q = store.computeIfAbsent(key, k -> new java.util.ArrayDeque<>());
            purgeExpired(q);
            while (q.size() >= MAX_PER_USER) {
                q.pollFirst();
            }
            q.addLast(new Entry(attachmentId, System.currentTimeMillis() + TTL_MS));
            return q.size();
        }

        /** 消费并清空（即使会话创建失败也算消费——截图时效短，不让失败请求反复挂旧图）。 */
        synchronized List<String> drain(String key) {
            java.util.Deque<Entry> q = store.remove(key);
            if (q == null) {
                return List.of();
            }
            purgeExpired(q);
            return q.stream().map(Entry::attachmentId).toList();
        }

        private void purgeExpired(java.util.Deque<Entry> q) {
            long now = System.currentTimeMillis();
            while (!q.isEmpty() && q.peekFirst().expireAt() < now) {
                q.pollFirst();
            }
        }
    }

    /**
     * FR-21 危险操作二次确认暂存：key=channel|chat|sender，单槽（新暂存覆盖旧暂存，旧的视为放弃），
     * TTL 10 分钟。纯内存（重启即清，危险操作本就不该跨重启悬而未决）；惰性过期。
     */
    static final class PendingConfirmStore {
        private static final long DEFAULT_TTL_MS = 10 * 60 * 1000;
        private final long ttlMillis;
        private final java.util.function.LongSupplier clock;
        private final Map<String, Entry> store = new java.util.concurrent.ConcurrentHashMap<>();

        /** 暂存的一次操作：指令名 + 原参数 + 给「取消」回显用的描述。 */
        record Entry(String name, String args, String description, long expireAt) {
        }

        PendingConfirmStore() {
            this(DEFAULT_TTL_MS, System::currentTimeMillis);
        }

        /** 测试用：短 TTL / 可控时钟。 */
        PendingConfirmStore(long ttlMillis, java.util.function.LongSupplier clock) {
            this.ttlMillis = ttlMillis;
            this.clock = clock;
        }

        synchronized void put(String key, String name, String args, String description) {
            store.put(key, new Entry(name, args, description, clock.getAsLong() + ttlMillis));
        }

        /** 取出并清除；无暂存或已过期返回 null（过期即视为没有，落 QA）。 */
        synchronized Entry poll(String key) {
            Entry e = store.remove(key);
            return e != null && e.expireAt() >= clock.getAsLong() ? e : null;
        }

        /** 「取消」用：存在且未过期才消费并返回 true。 */
        synchronized boolean removeFresh(String key) {
            return poll(key) != null;
        }

        /** 交互日志打标用：裸文本是确认/取消且有未过期暂存时，回指令名（确认）或 cancel。 */
        synchronized String peekName(String key, String text) {
            if (!"确认".equals(text) && !"取消".equals(text)) {
                return null;
            }
            Entry e = store.get(key);
            if (e == null || e.expireAt() < clock.getAsLong()) {
                return null;
            }
            return "确认".equals(text) ? e.name() : "cancel";
        }
    }

    // ---------------- 回推 ----------------

    /** 回推回复；返回 null=成功，否则为失败原因（调用方据此把交互行改落 FAILED）。 */
    private String reply(BotMessage msg, String text) {
        BotAdapter adapter = adapterOf(msg.channel());
        if (adapter == null) {
            return "无 bot 适配器 " + msg.channel();
        }
        try {
            adapter.reply(msg, truncate(text));
            return null;
        } catch (Exception e) {
            log.error("bot 回复发送失败: channel={} chat={} err={}",
                    msg.channel(), msg.externalChatId(), e.getMessage(), e);
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    /**
     * FR-16 出站图片逐张回推：单张失败记日志继续；有失败补一条文字提示
     * （主回答已送达，图片属增强信息，不翻交互行 FAILED）。
     */
    private void sendImages(BotMessage msg, List<OutImage> images) {
        BotAdapter adapter = adapterOf(msg.channel());
        if (adapter == null) {
            return;
        }
        int failed = 0;
        for (OutImage img : images) {
            try {
                adapter.replyImage(msg, img.fileName(), img.contentType(), img.bytes());
            } catch (Exception e) {
                failed++;
                log.error("bot 图片回推失败: channel={} chat={} name={} err={}",
                        msg.channel(), msg.externalChatId(), img.fileName(), e.getMessage(), e);
            }
        }
        if (failed > 0) {
            try {
                adapter.reply(msg, "⚠️ " + failed + " 张需求截图发送失败，请到平台需求页查看");
            } catch (Exception e) {
                log.error("bot 图片失败提示发送失败: channel={} err={}", msg.channel(), e.getMessage());
            }
        }
    }

    /** 按 channel 找适配器；找不到记告警返回 null（调用方各自决定兜底文案）。 */
    private BotAdapter adapterOf(String channel) {
        BotAdapterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            log.warn("bot 注册表未装配，回复丢弃: channel={}", channel);
            return null;
        }
        BotAdapter adapter = registry.get(channel).orElse(null);
        if (adapter == null) {
            log.warn("无 bot 适配器 {}，回复丢弃", channel);
        }
        return adapter;
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
