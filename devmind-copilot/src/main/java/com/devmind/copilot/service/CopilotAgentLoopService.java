package com.devmind.copilot.service;

import com.devmind.common.agent.gateway.AgentCard;
import com.devmind.common.agent.gateway.AgentCardAction;
import com.devmind.common.agent.gateway.AgentCardButton;
import com.devmind.common.agent.gateway.AgentGateway;
import com.devmind.common.agent.gateway.AgentReply;
import com.devmind.common.agent.gateway.AgentRequest;
import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolAccess;
import com.devmind.common.agent.tool.ToolAccessPolicy;
import com.devmind.common.agent.tool.ToolArgs;
import com.devmind.common.agent.tool.ToolCall;
import com.devmind.common.agent.tool.ToolContext;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.agent.tool.ToolRisk;
import com.devmind.common.agent.tool.ToolSpec;
import com.devmind.common.model.ModelCallException;
import com.devmind.common.model.OpenAiCompatToolChat;
import com.devmind.common.model.OpenAiCompatToolChat.Completion;
import com.devmind.common.model.OpenAiCompatToolChat.Message;
import com.devmind.common.model.OpenAiCompatToolChat.Options;
import com.devmind.common.model.OpenAiCompatToolChat.ToolDef;
import com.devmind.copilot.config.CopilotProperties;
import com.devmind.copilot.model.CopilotActionEntity;
import com.devmind.copilot.model.CopilotConversationEntity;
import com.devmind.copilot.model.CopilotMessageEntity;
import com.devmind.copilot.model.CopilotToolCallEntity;
import com.devmind.copilot.model.CopilotTurnEntity;
import com.devmind.copilot.repo.CopilotActionRepository;
import com.devmind.copilot.repo.CopilotConversationRepository;
import com.devmind.copilot.repo.CopilotMessageRepository;
import com.devmind.copilot.repo.CopilotToolCallRepository;
import com.devmind.copilot.repo.CopilotTurnRepository;
import com.devmind.copilot.tool.ToolRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 智能体对话循环（CAP-76 FR-06~FR-21）：身份/限流 → 校验 → 模型工具循环 → 确认单。
 *
 * <p>硬约束：本类方法<b>禁止</b> {@code @Transactional}（工具可能触发异步作业，外层事务会让异步线程
 * 看不到未提交行，CAP-71/CAP-76 红线）；写操作只落确认单，执行统一走
 * {@link CopilotActionService#execute}（原子抢占 + 全量重校验）。</p>
 */
@Service
public class CopilotAgentLoopService implements AgentGateway {

    private static final Logger log = LoggerFactory.getLogger(CopilotAgentLoopService.class);

    private static final Pattern CONFIRM = Pattern.compile("^确认(?:\\s+([A-Za-z0-9-]+))?$");
    private static final Pattern CANCEL = Pattern.compile("^取消(?:\\s+([A-Za-z0-9-]+))?$");
    /**
     * 平台确认单文本指纹（ confirmations() 下发格式）：单行/多行/分片三种形态。
     * 用于两处：历史回放消毒 + 模型幻觉识别（弱模型抄历史里的格式编造假短码，用户确认查无此单）。
     */
    private static final Pattern CONFIRM_FORMAT = Pattern.compile(
            "(?m)^[\\s>#*-]*待确认\\s*(\\[|\\d+\\s*个操作)|回复「确认|请使用完整短码逐张确认");
    /** 历史回放时确认单原文的替身：保留事实、抹掉可模仿的格式与短码。 */
    private static final String CONFIRM_PLACEHOLDER =
            "（平台已下发写操作确认单并等待用户确认；确认单与短码由平台生成，无需关注其格式）";
    private static final int MAX_LINKS = 5;
    private static final int MAX_MESSAGE_QUERY = 100;

    private final CopilotProperties props;
    private final CopilotIdentityService identities;
    private final CopilotEndpointService endpoints;
    private final ToolRegistry registry;
    private final CopilotToolRunner runner;
    private final CopilotActionService actions;
    private final CopilotActionRepository actionRepo;
    private final CopilotTurnRepository turns;
    private final CopilotMessageRepository messages;
    private final CopilotConversationRepository conversations;
    private final CopilotToolCallRepository toolCalls;
    private final ObjectProvider<ToolAccessPolicy> policies;
    private final ObjectMapper mapper = new ObjectMapper();

    /** FR-31：同一 (channel, chat, sender) 同时只允许一轮。按会话键分段，进程内有效。 */
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    /** FR-31：全局模型调用并发上限（有界，用完排队，超时拒绝）。 */
    private final Semaphore modelSlots;

    public CopilotAgentLoopService(CopilotProperties props,
                                   CopilotIdentityService identities,
                                   CopilotEndpointService endpoints,
                                   ToolRegistry registry,
                                   CopilotToolRunner runner,
                                   CopilotActionService actions,
                                   CopilotActionRepository actionRepo,
                                   CopilotTurnRepository turns,
                                   CopilotMessageRepository messages,
                                   CopilotConversationRepository conversations,
                                   CopilotToolCallRepository toolCalls,
                                   ObjectProvider<ToolAccessPolicy> policies) {
        this.props = props;
        this.identities = identities;
        this.endpoints = endpoints;
        this.registry = registry;
        this.runner = runner;
        this.actions = actions;
        this.actionRepo = actionRepo;
        this.turns = turns;
        this.messages = messages;
        this.conversations = conversations;
        this.toolCalls = toolCalls;
        this.policies = policies;
        this.modelSlots = new Semaphore(Math.max(1, props.getMaxConcurrentModel()), true);
    }

    @Override
    public boolean enabled() {
        return props.isEnabled();
    }

    @Override
    public boolean replyProgressCard() {
        return props.isReplyProgressCard();
    }

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    @Override
    public AgentReply handle(AgentRequest request) {
        if (!enabled()) {
            return AgentReply.text("智能体未启用");
        }
        String missing = missingScope(request.channel(), request.externalChatId(), request.senderId());
        if (missing != null) {
            return AgentReply.text(missing);
        }
        String text = request.text() == null ? "" : request.text().trim();
        if (text.isEmpty()) {
            return AgentReply.text("请描述你想查询或操作的内容");
        }
        ReentrantLock lock = lockFor(request.channel(), request.externalChatId(), request.senderId());
        if (!lock.tryLock()) {
            return AgentReply.text("上一条消息还在处理，请稍后再试");
        }
        try {
            CopilotIdentityService.Identity identity = identities.resolve(request.actor());
            if (rateLimited(identity.actor())) {
                return AgentReply.text("操作太频繁，请稍后再试（每分钟最多 "
                        + props.getMaxTurnsPerMinute() + " 轮）");
            }
            AgentReply confirm = parseConfirmOrCancel(request, identity, text);
            if (confirm != null) {
                return confirm;
            }
            return runModel(request, identity, text);
        } catch (CopilotEndpointService.EndpointUnavailableException e) {
            return AgentReply.text("智能体暂不可用：" + e.getMessage());
        } catch (Exception e) {
            log.error("智能体处理异常: channel={} sender={} err={}",
                    request.channel(), request.senderId(), e.toString());
            return AgentReply.text("智能体处理失败，请稍后再试或到平台查看日志");
        } finally {
            lock.unlock();
        }
    }

    @Override
    public AgentReply onCardAction(AgentCardAction action) {
        if (!enabled()) {
            return AgentReply.text("智能体未启用");
        }
        String missing = missingScope(action.channel(), action.externalChatId(), action.senderId());
        if (missing != null) {
            return AgentReply.text(missing);
        }
        if (action.actionId() == null || action.actionId().isBlank()) {
            return AgentReply.text("卡片操作缺少确认单");
        }
        ReentrantLock lock = lockFor(action.channel(), action.externalChatId(), action.senderId());
        if (!lock.tryLock()) {
            return AgentReply.text("上一条消息还在处理，请稍后再试");
        }
        try {
            CopilotIdentityService.Identity identity = identities.resolve(action.actor());
            if (rateLimited(identity.actor())) {
                return AgentReply.text("操作太频繁，请稍后再试");
            }
            Optional<CopilotActionEntity> found = actionRepo.findById(action.actionId());
            if (found.isEmpty()) {
                return AgentReply.text("确认单不存在或已过期，请重新发起");
            }
            CopilotActionEntity target = found.get();
            if (!action.senderId().equals(target.getSenderId())
                    || !eq(action.channel(), target.getChannel())
                    || !eq(action.externalChatId(), target.getExternalChatId())) {
                return AgentReply.text("只有确认单发起人可以操作");
            }
            if (AgentCardButton.CANCEL.equals(action.operation())) {
                return cancelByCard(action, identity, target);
            }
            if (!AgentCardButton.CONFIRM.equals(action.operation())) {
                return AgentReply.text("不支持的卡片操作");
            }
            AgentRequest req = fromCard(action, target);
            return executeConfirmed(req, identity, target,
                    "CARD_CONFIRM:" + target.getShortCode(), true);
        } catch (Exception e) {
            log.error("卡片操作处理异常: actionId={} err={}", action.actionId(), e.toString());
            return AgentReply.text("操作失败，请稍后再试或到平台查看日志");
        } finally {
            lock.unlock();
        }
    }

    @Override
    public AgentReply cancel(AgentRequest request, String shortCode) {
        if (!enabled()) {
            return AgentReply.text("智能体未启用");
        }
        String missing = missingScope(request.channel(), request.externalChatId(), request.senderId());
        if (missing != null) {
            return AgentReply.text(missing);
        }
        ReentrantLock lock = lockFor(request.channel(), request.externalChatId(), request.senderId());
        if (!lock.tryLock()) {
            return AgentReply.text("上一条消息还在处理，请稍后再试");
        }
        try {
            if (shortCode != null && !shortCode.isBlank()) {
                return cancelByCode(request.channel(), request.externalChatId(), request.senderId(), shortCode);
            }
            List<CopilotActionEntity> pending = actions.allPendingForDisplay(
                    request.channel(), request.externalChatId(), request.senderId());
            if (pending.isEmpty()) {
                return AgentReply.text("没有待取消的确认单");
            }
            if (pending.size() > 1) {
                return AgentReply.text(pendingList(pending, "有多张待确认单，请回复：/cancel <完整短码>"));
            }
            CopilotActionEntity only = pending.get(0);
            String reason = actions.cancel(only.getId());
            return AgentReply.text(reason == null
                    ? "已取消 [" + only.getShortCode() + "] " + titleOfPending(only) + "，操作未执行"
                    : reason);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void reset(AgentRequest request) {
        if (request == null || request.channel() == null || request.senderId() == null) {
            return;
        }
        conversations.findByChannelAndExternalChatIdAndSenderId(
                request.channel(), request.externalChatId(), request.senderId()).ifPresent(conv -> {
            conv.setStatus("ARCHIVED");
            conversations.save(conv);
            messages.deleteByConversationId(conv.getId());
            log.info("智能体会话已归档: conversationId={} channel={} sender={}",
                    conv.getId(), request.channel(), request.senderId());
        });
    }

    // ------------------------------------------------------------------
    // 文本确认 / 取消（FR-17，先于模型）
    // ------------------------------------------------------------------

    private AgentReply parseConfirmOrCancel(AgentRequest req, CopilotIdentityService.Identity identity,
                                            String text) {
        Matcher confirm = CONFIRM.matcher(text);
        if (confirm.matches()) {
            String code = confirm.group(1);
            if (code == null || code.isBlank()) {
                List<CopilotActionEntity> pending = actions.allPendingForDisplay(
                        req.channel(), req.externalChatId(), req.senderId());
                if (pending.isEmpty()) {
                    return null; // 裸「确认」也可能只是聊天内容
                }
                if (pending.size() > 1) {
                    return AgentReply.text(pendingList(pending, "有多张待确认单，请回复：确认 <完整短码>"));
                }
                CopilotActionEntity only = pending.get(0);
                if (ToolRisk.DANGEROUS.name().equals(only.getRisk())) {
                    return AgentReply.text("危险操作请回复：确认 " + only.getShortCode());
                }
                return executeConfirmed(req, identity, only, "确认 " + only.getShortCode(), false);
            }
            return confirmByCode(req, identity, code);
        }
        Matcher cancel = CANCEL.matcher(text);
        if (cancel.matches()) {
            String code = cancel.group(1);
            if (code == null || code.isBlank()) {
                List<CopilotActionEntity> pending = actions.allPendingForDisplay(
                        req.channel(), req.externalChatId(), req.senderId());
                if (pending.isEmpty()) {
                    return null;
                }
                if (pending.size() > 1) {
                    return AgentReply.text(pendingList(pending, "有多张待确认单，请回复：取消 <完整短码>"));
                }
                CopilotActionEntity only = pending.get(0);
                String reason = actions.cancel(only.getId());
                return AgentReply.text(reason == null
                        ? "已取消 [" + only.getShortCode() + "]，操作未执行" : reason);
            }
            return cancelByCode(req.channel(), req.externalChatId(), req.senderId(), code);
        }
        return null;
    }

    private AgentReply confirmByCode(AgentRequest req, CopilotIdentityService.Identity identity, String rawCode) {
        String code = CopilotActionService.normalizeCode(rawCode);
        if (!code.contains("-") && actions.isPrefixOnly(
                req.channel(), req.externalChatId(), req.senderId(), code)) {
            return AgentReply.text("「" + code + "」是批量短码前缀，不是确认指令。请使用完整短码，如：确认 "
                    + code + "-1");
        }
        Optional<CopilotActionEntity> found = actions.findInScope(
                req.channel(), req.externalChatId(), req.senderId(), code);
        if (found.isEmpty()) {
            return AgentReply.text(terminalActionMessage(req, code)
                    .orElse("确认单不存在或已过期，请重新发起"));
        }
        return executeConfirmed(req, identity, found.get(), "确认 " + code, false);
    }

    private AgentReply cancelByCode(String channel, String chatId, String senderId, String rawCode) {
        String code = CopilotActionService.normalizeCode(rawCode);
        if (!code.contains("-") && actions.isPrefixOnly(channel, chatId, senderId, code)) {
            return AgentReply.text("「" + code + "」是批量短码前缀，请使用完整短码，如：取消 " + code + "-1");
        }
        Optional<CopilotActionEntity> found = actions.findInScope(channel, chatId, senderId, code);
        if (found.isPresent()) {
            String reason = actions.cancel(found.get().getId());
            return AgentReply.text(reason == null ? "已取消 [" + code + "]，操作未执行" : reason);
        }
        return AgentReply.text(terminalActionMessage(channel, chatId, senderId, code)
                .orElse("确认单不存在或已过期"));
    }

    private Optional<String> terminalActionMessage(AgentRequest req, String code) {
        return terminalActionMessage(req.channel(), req.externalChatId(), req.senderId(), code);
    }

    private Optional<String> terminalActionMessage(String channel, String chatId, String senderId, String code) {
        return actionRepo
                .findFirstByChannelAndExternalChatIdAndSenderIdAndShortCodeOrderByCreatedAtDesc(
                        channel, chatId, senderId, code)
                .map(a -> switch (a.getStatus()) {
                    case CopilotActionEntity.EXPIRED -> "确认单 [" + code + "] 已过期，请重新发起";
                    case CopilotActionEntity.CANCELLED -> "确认单 [" + code + "] 已取消";
                    case CopilotActionEntity.SUCCEEDED ->
                            "确认单 [" + code + "] 已执行，请勿重复确认（结果：" + oneLine(a.getResultJson(), 120) + "）";
                    case CopilotActionEntity.FAILED ->
                            "确认单 [" + code + "] 执行失败，请重新发起（"
                                    + (a.getError() == null ? "原因未知" : a.getError()) + "）";
                    case CopilotActionEntity.EXECUTING -> "确认单 [" + code + "] 正在执行，请稍后查看结果";
                    default -> null;
                });
    }

    // ------------------------------------------------------------------
    // 确认执行（文本 / 卡片共用）
    // ------------------------------------------------------------------

    private AgentReply executeConfirmed(AgentRequest req, CopilotIdentityService.Identity identity,
                                        CopilotActionEntity action, String userText, boolean cardPath) {
        CopilotConversationEntity conv = conversationOf(req.channel(), req.externalChatId(),
                req.senderId(), identity.actor(), action.getProjectId());
        CopilotTurnEntity turn = newTurn(conv.getId(), req.channel(), req.externalChatId(), req.senderId(),
                identity.actor(), action.getProjectId(),
                cardPath ? CopilotTurnEntity.TRIGGER_CARD_ACTION : CopilotTurnEntity.TRIGGER_CONFIRM,
                CopilotJson.maskText(CopilotJson.truncate(userText, 4000)));
        long t0 = System.currentTimeMillis();

        CopilotActionService.Principal principal = new CopilotActionService.Principal(
                identity.actor(), identity.role(), req.personalActor(), req.identitySupported());
        ToolContext ctx = new ToolContext(turn.getId(), action.getId(), identity.actor(), identity.role(),
                req.channel(), req.externalChatId(), req.senderId(), req.personalActor(),
                action.getProjectId(), req.projectName(), Instant.now());

        CopilotActionService.Outcome outcome;
        try {
            outcome = actions.execute(action.getId(), principal, ctx, turn.getId(), cardPath);
        } catch (Exception e) {
            log.error("确认单执行异常: actionId={} err={}", action.getId(), e.toString());
            String message = "执行失败，请稍后再试或到平台查看确认单状态";
            turn.setStatus(CopilotTurnEntity.FAILED);
            turn.setError(message);
            turn.setDurationMs(System.currentTimeMillis() - t0);
            turns.save(turn);
            return AgentReply.text(message);
        }

        String receipt = outcome.message() == null || outcome.message().isBlank()
                ? (outcome.success() ? "已执行" : "执行失败") : outcome.message();
        turn.setStatus(outcome.success() ? CopilotTurnEntity.OK : CopilotTurnEntity.FAILED);
        turn.setAssistantText(CopilotJson.maskText(CopilotJson.truncate(receipt, 8000)));
        turn.setDurationMs(System.currentTimeMillis() - t0);
        if (!outcome.success()) {
            turn.setError(CopilotJson.truncate(receipt, 2000));
        }
        turns.save(turn);
        saveMessage(conv.getId(), "assistant", CopilotJson.maskText(CopilotJson.truncate(receipt, 4000)));

        String footer = "确认单 " + action.getShortCode() + " · " + ctx.actor();
        if (cardPath) {
            AgentCard card = new AgentCard(
                    outcome.success() ? "执行成功 [" + action.getShortCode() + "]" : "执行失败 [" + action.getShortCode() + "]",
                    outcome.success() ? AgentCard.SUCCESS : AgentCard.DANGER,
                    receipt, linkButtons(outcome.result()), footer);
            return AgentReply.of(receipt, card);
        }
        return AgentReply.text(receipt);
    }

    private AgentReply cancelByCard(AgentCardAction action, CopilotIdentityService.Identity identity,
                                    CopilotActionEntity target) {
        CopilotConversationEntity conv = conversationOf(action.channel(), action.externalChatId(),
                action.senderId(), identity.actor(), target.getProjectId());
        CopilotTurnEntity turn = newTurn(conv.getId(), action.channel(), action.externalChatId(),
                action.senderId(), identity.actor(), target.getProjectId(),
                CopilotTurnEntity.TRIGGER_CARD_ACTION,
                "CARD_CANCEL:" + target.getShortCode());
        long t0 = System.currentTimeMillis();
        String reason = actions.cancel(target.getId());
        String receipt = reason == null ? "已取消，操作未执行" : reason;
        turn.setStatus(reason == null ? CopilotTurnEntity.OK : CopilotTurnEntity.FAILED);
        turn.setAssistantText(CopilotJson.maskText(CopilotJson.truncate(receipt, 8000)));
        turn.setDurationMs(System.currentTimeMillis() - t0);
        turns.save(turn);
        saveMessage(conv.getId(), "assistant", receipt);
        AgentCard card = new AgentCard("已取消 [" + target.getShortCode() + "]",
                AgentCard.INFO, receipt, List.of(), "确认单 " + target.getShortCode());
        return AgentReply.of(receipt, card);
    }

    private AgentRequest fromCard(AgentCardAction action, CopilotActionEntity target) {
        return new AgentRequest(action.channel(), null, action.externalChatId(), action.senderId(),
                action.actor(), action.personalActor(), action.identitySupported(), "",
                target.getProjectId(), null, null, action.eventId());
    }

    // ------------------------------------------------------------------
    // 模型循环（FR-06/FR-07）
    // ------------------------------------------------------------------

    private AgentReply runModel(AgentRequest req, CopilotIdentityService.Identity identity, String userText) {
        CopilotEndpointService.Resolved resolved = endpoints.resolve();
        CopilotConversationEntity conv = conversationOf(req.channel(), req.externalChatId(),
                req.senderId(), identity.actor(), req.projectId());
        CopilotTurnEntity turn = newTurn(conv.getId(), req.channel(), req.externalChatId(), req.senderId(),
                identity.actor(), req.projectId(), CopilotTurnEntity.TRIGGER_USER,
                CopilotJson.maskText(CopilotJson.truncate(userText, 4000)));
        turn.setModel(resolved.view().model());
        turn.setEndpointId(String.valueOf(resolved.view().id()));
        saveMessage(conv.getId(), "user", CopilotJson.maskText(CopilotJson.truncate(userText, 4000)));

        Run run = new Run(req, identity, conv, turn, userText);
        run.deadline = Instant.now().plusSeconds(Math.max(5, props.getTurnTimeoutSeconds()));
        run.mode = resolved.toolMode();
        resetMessages(run, run.mode);

        try {
            AgentReply reply = "json".equals(run.mode) ? jsonLoop(run) : nativeLoop(run, resolved);
            if (reply == null) {
                reply = AgentReply.text("本轮未产生回复，请重新描述你的问题");
            }
            return reply;
        } finally {
            if (run.turn.getDurationMs() == 0) {
                run.turn.setDurationMs(Duration.between(run.startedAt, Instant.now()).toMillis());
            }
            run.turn.setSteps(run.steps);
            run.turn.setPromptTokens(run.promptTokens);
            run.turn.setCompletionTokens(run.completionTokens);
            run.turn.setToolMode(run.mode);
            if (run.turn.getStatus() == null) {
                run.turn.setStatus(CopilotTurnEntity.FAILED);
            }
            turns.save(run.turn);
            conv.setLastTurnAt(Instant.now());
            conversations.save(conv);
        }
    }

    private AgentReply nativeLoop(Run run, CopilotEndpointService.Resolved resolved) {
        List<ToolDef> defs = toolDefs(run.identity.role());
        boolean auto = "auto".equalsIgnoreCase(
                props.getToolMode() == null ? "auto" : props.getToolMode().trim());
        while (true) {
            AgentReply stop = preflight(run);
            if (stop != null) {
                return stop;
            }
            Options options = options(resolved, run);
            Completion completion;
            if (!acquireModelSlot(run)) {
                return failTurn(run, "当前请求较多，请稍后再试");
            }
            try {
                completion = OpenAiCompatToolChat.complete(options, run.messages, defs);
            } catch (OpenAiCompatToolChat.HttpStatusException e) {
                if (auto && e.toolUnsupported()) {
                    endpoints.degradeToJson(resolved.view());
                    run.mode = "json";
                    resetMessages(run, "json");
                    return jsonLoop(run);
                }
                return failTurn(run, "模型调用失败：" + safeModelError(e));
            } catch (ModelCallException e) {
                return failTurn(run, "模型调用失败：" + safeModelError(e));
            } finally {
                modelSlots.release();
            }
            recordUsage(run, completion);
            if (!completion.hasToolCalls()) {
                AgentReply guarded = guardHallucinatedConfirmation(run, completion.content(),
                        completion.content());
                if (guarded != null) {
                    return guarded;
                }
                continue;
            }
            run.messages.add(Message.assistant(completion.content(), completion.toolCalls()));
            AgentReply pending = processToolCalls(run, completion.toolCalls());
            if (pending != null) {
                return pending;
            }
        }
    }

    private AgentReply jsonLoop(Run run) {
        int corrections = 0;
        while (true) {
            AgentReply stop = preflight(run);
            if (stop != null) {
                return stop;
            }
            CopilotEndpointService.Resolved resolved = endpoints.resolve();
            Options options = options(resolved, run);
            Completion completion;
            if (!acquireModelSlot(run)) {
                return failTurn(run, "当前请求较多，请稍后再试");
            }
            try {
                completion = OpenAiCompatToolChat.complete(options, run.messages, List.of());
            } catch (ModelCallException e) {
                return failTurn(run, "模型调用失败：" + safeModelError(e));
            } finally {
                modelSlots.release();
            }
            recordUsage(run, completion);
            JsonDecision decision;
            try {
                decision = parseDecision(completion.content());
            } catch (Exception e) {
                corrections++;
                if (corrections > 1) {
                    return failTurn(run, "模型输出无法解析，请重新描述你的问题");
                }
                run.messages.add(Message.assistant(completion.content()));
                run.messages.add(Message.user(
                        "你的输出不是合法 JSON。请只输出一个 JSON 对象："
                                + "{\"tool\":\"工具名\",\"args\":{...}} 或 {\"answer\":\"最终回答\"}"));
                continue;
            }
            if (decision.answer() != null) {
                AgentReply guarded = guardHallucinatedConfirmation(run, completion.content(),
                        decision.answer());
                if (guarded != null) {
                    return guarded;
                }
                continue;
            }
            ToolCall call = new ToolCall("json_" + CopilotIds.newId(), decision.tool(), decision.args());
            run.messages.add(Message.assistant(completion.content()));
            AgentReply pending = processToolCalls(run, List.of(call));
            if (pending != null) {
                return pending;
            }
        }
    }

    /** 每步前置检查：步数、整轮超时。 */
    private AgentReply preflight(Run run) {
        if (run.steps >= Math.max(1, props.getMaxSteps())) {
            return finishMaxSteps(run);
        }
        if (Instant.now().isAfter(run.deadline)) {
            return failTurn(run, "本轮处理超时，请缩小范围或补充条件后重试");
        }
        return null;
    }

    private void recordUsage(Run run, Completion completion) {
        run.steps++;
        run.promptTokens += completion.promptTokens();
        run.completionTokens += completion.completionTokens();
    }

    private Options options(CopilotEndpointService.Resolved resolved, Run run) {
        int remain = (int) Math.max(1, Duration.between(Instant.now(), run.deadline).toSeconds());
        int timeout = Math.max(1, Math.min(props.getModelTimeoutSeconds(), remain));
        var view = resolved.view();
        return new Options(view.baseUrl(), view.apiKey(), view.model(), timeout, props.getTemperature());
    }

    private boolean acquireModelSlot(Run run) {
        try {
            long wait = Math.max(1, Math.min(15,
                    Duration.between(Instant.now(), run.deadline).toSeconds()));
            return modelSlots.tryAcquire(wait, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String safeModelError(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String first = message.split("\\R", 2)[0];
        return first.length() <= 300 ? first : first.substring(0, 300) + "…";
    }

    // ------------------------------------------------------------------
    // 工具调用（FR-04/07/09/10/11/20/21）
    // ------------------------------------------------------------------

    /** @return 非 null = 本轮结束（确认单/上限/拒绝）；null = 继续模型循环 */
    private AgentReply processToolCalls(Run run, List<ToolCall> calls) {
        List<ToolCall> considered = new ArrayList<>();
        int cap = Math.max(1, props.getMaxToolCallsPerStep());
        for (int i = 0; i < calls.size(); i++) {
            ToolCall call = calls.get(i);
            if (i >= cap) {
                appendToolMessage(run, call, errorText("单步工具调用超过上限（" + cap + " 个），未执行"));
                auditRejected(run, call, "单步工具调用超过上限");
                continue;
            }
            considered.add(call);
        }

        List<WriteProposal> writes = new ArrayList<>();
        for (ToolCall call : considered) {
            Optional<PlatformTool> found = registry.find(call.name());
            if (found.isEmpty()) {
                appendToolMessage(run, call, errorText("未知工具：" + call.name()));
                auditRejected(run, call, "未知工具");
                continue;
            }
            PlatformTool tool = found.get();
            if (ToolRegistry.rank(run.identity.role()) < ToolRegistry.rank(tool.spec().minRole())) {
                appendToolMessage(run, call, errorText("当前角色无权调用工具：" + call.name()));
                auditRejected(run, call, "PERMISSION_DENIED");
                continue;
            }
            Map<String, Object> args;
            try {
                args = ToolArgs.validate(tool.spec().parametersSchema(), call.args());
            } catch (ToolArgs.InvalidArgumentException e) {
                appendToolMessage(run, call, errorText("参数不合法：" + e.getMessage()));
                auditToolCall(run, call, tool.spec().name(), call.args(), tool.spec().risk(),
                        CopilotToolCallEntity.FAILED, null, "参数不合法：" + e.getMessage(), 0);
                continue;
            }
            String dedupeKey = call.name() + "|" + CopilotJson.write(args);
            int count = run.callCounts.merge(dedupeKey, 1, Integer::sum);
            if (count > 2) {
                appendToolMessage(run, call, errorText("同一工具与参数重复调用超过 2 次，未执行"));
                auditRejected(run, call, "重复调用超过上限");
                continue;
            }
            ToolRisk risk = tool.spec().riskFor(args);
            String projectId = resolveProject(tool, args, run);
            if (tool.spec().requiresProject() && isBlank(projectId)) {
                String hint = "该操作需要项目上下文。请先询问用户要操作哪个项目，或调用 list_projects 查询；"
                        + "禁止猜测项目 id。";
                appendToolMessage(run, call, errorText(hint));
                auditToolCall(run, call, tool.spec().name(), args, risk,
                        CopilotToolCallEntity.REJECTED, null, hint, 0);
                continue;
            }
            if (risk.writes()) {
                writes.add(new WriteProposal(tool, call, args, risk, projectId));
            } else {
                AgentReply stop = executeRead(run, tool, call, args, risk, projectId);
                if (stop != null) {
                    return stop;
                }
            }
        }

        if (!writes.isEmpty()) {
            return confirmations(run, writes);
        }
        return null;
    }

    private AgentReply executeRead(Run run, PlatformTool tool, ToolCall call, Map<String, Object> args,
                                   ToolRisk risk, String projectId) {
        ToolResult denied = ToolAccess.check(policies.getIfAvailable(), run.identity.actor(),
                projectId, risk, tool.spec().name());
        if (denied != null) {
            appendToolMessage(run, call, errorText(denied.errorMessage()));
            auditToolCall(run, call, tool.spec().name(), args, risk,
                    CopilotToolCallEntity.REJECTED, null, denied.errorMessage(), 0);
            return null;
        }
        ToolContext ctx = toolContext(run, null, projectId);
        long t0 = System.currentTimeMillis();
        ToolResult result = runner.run(tool, call, ctx);
        long duration = System.currentTimeMillis() - t0;
        String content = result.success()
                ? CopilotJson.maskText(CopilotJson.truncate(
                        result.summary() == null ? "" : result.summary(), props.getMaxToolResultChars()))
                : errorText(result.errorCode() + "：" + nullSafe(result.errorMessage()));
        appendToolMessage(run, call, content);
        collectLink(run, result);
        auditToolCall(run, call, tool.spec().name(), args, risk,
                result.success() ? CopilotToolCallEntity.OK : CopilotToolCallEntity.FAILED,
                result.summary(), result.errorMessage(), duration);
        return null;
    }

    private AgentReply confirmations(Run run, List<WriteProposal> writes) {
        if (!run.identity.active()) {
            return failTurn(run, "你的平台账号不存在或已停用，不能执行写操作。请先 /bindme 绑定身份或联系管理员");
        }
        CopilotActionService.Principal principal = new CopilotActionService.Principal(
                run.identity.actor(), run.identity.role(), run.req.personalActor(),
                run.req.identitySupported());
        if (!actions.writeAllowed(principal)) {
            return failTurn(run, "写操作需要先绑定个人身份：发 /bindme 获取身份码，"
                    + "然后登录平台 设置 → 第三方账号 绑定");
        }

        // FR-10：同一批次只允许一个项目
        Set<String> projectIds = new LinkedHashSet<>();
        for (WriteProposal w : writes) {
            if (!isBlank(w.projectId())) {
                projectIds.add(w.projectId());
            }
        }
        if (projectIds.size() > 1) {
            return failTurn(run, "跨项目批量写不支持，请分开确认");
        }

        int maxWrites = Math.max(1, props.getMaxWritesPerTurn());
        List<WriteProposal> accepted = new ArrayList<>();
        List<String> rejectedNotes = new ArrayList<>();
        for (int i = 0; i < writes.size(); i++) {
            WriteProposal w = writes.get(i);
            if (i >= maxWrites) {
                String message = "本轮写操作过多，请优先处理已给出的确认单";
                appendToolMessage(run, w.call(), errorText(message));
                auditRejected(run, w.call(), message);
                rejectedNotes.add(message);
                continue;
            }
            ToolResult denied = ToolAccess.check(policies.getIfAvailable(), run.identity.actor(),
                    w.projectId(), w.risk(), w.tool().spec().name());
            if (denied != null) {
                appendToolMessage(run, w.call(), errorText(denied.errorMessage()));
                auditRejected(run, w.call(), denied.errorMessage());
                rejectedNotes.add(denied.errorMessage());
                continue;
            }
            accepted.add(w);
        }
        if (accepted.isEmpty()) {
            return failTurn(run, "没有可执行的写操作：" + String.join("；", rejectedNotes));
        }

        int maxChunks = Math.max(1, props.getConfirmPreviewMaxChunks());
        int chunkChars = Math.max(200, props.getConfirmPreviewChunkChars());
        Map<WriteProposal, List<String>> plans = new LinkedHashMap<>();
        for (WriteProposal w : accepted) {
            ToolContext ctx = toolContext(run, null, w.projectId());
            String preview = w.tool().preview(w.call(), ctx);
            if (preview == null || preview.isBlank()) {
                preview = actions.previewOf(w.tool(), w.args());
            }
            List<String> parts = splitChunks(preview, chunkChars);
            if (parts.size() > maxChunks) {
                String message = "操作预览过长（" + preview.length() + " 字，超过上限），请拆分后重试";
                appendToolMessage(run, w.call(), errorText(message));
                auditRejected(run, w.call(), message);
                rejectedNotes.add(message);
                continue;
            }
            plans.put(w, parts);
        }
        if (plans.isEmpty()) {
            return failTurn(run, String.join("；", rejectedNotes));
        }

        List<WriteProposal> finalWrites = new ArrayList<>(plans.keySet());
        boolean multi = finalWrites.size() > 1;
        String baseCode = uniquePrefix(run);
        Map<WriteProposal, String> codes = new LinkedHashMap<>();
        for (int i = 0; i < finalWrites.size(); i++) {
            codes.put(finalWrites.get(i), multi ? baseCode + "-" + (i + 1) : baseCode);
        }

        // 创建确认单（先创建再回复；创建失败整体失败，不留半张卡）
        List<CopilotActionEntity> created = new ArrayList<>();
        Map<WriteProposal, CopilotActionEntity> actionByProposal = new LinkedHashMap<>();
        for (WriteProposal w : finalWrites) {
            String code = codes.get(w);
            String preview = String.join("", plans.get(w));
            CopilotActionEntity entity = actions.create(run.turn.getId(), run.req.channel(),
                    run.req.externalChatId(), run.req.senderId(), run.identity.actor(), w.projectId(),
                    w.tool().spec().name(), w.args(), w.risk(), code, preview);
            created.add(entity);
            actionByProposal.put(w, entity);
        }

        int totalParts = plans.values().stream().mapToInt(List::size).sum();
        int totalPreviewChars = plans.values().stream()
                .flatMap(List::stream).mapToInt(String::length).sum();
        boolean useChunks = totalParts > created.size() || totalPreviewChars > 1500;
        String instruction = multi
                ? "请使用完整短码逐张确认（如：确认 " + codes.get(finalWrites.get(0))
                        + "）；" + props.getConfirmTtlMinutes() + " 分钟内有效。"
                : "回复「确认 " + codes.get(finalWrites.get(0)) + "」执行，或「取消 "
                        + codes.get(finalWrites.get(0)) + "」。" + props.getConfirmTtlMinutes() + " 分钟内有效。";

        AgentCard card = confirmationCard(finalWrites, codes, plans, actionByProposal);
        String promptText;
        List<String> chunks = List.of();
        if (useChunks) {
            List<String> out = new ArrayList<>();
            for (WriteProposal w : finalWrites) {
                String code = codes.get(w);
                List<String> parts = plans.get(w);
                for (int i = 0; i < parts.size(); i++) {
                    out.add(parts.size() > 1
                            ? "[" + code + " " + (i + 1) + "/" + parts.size() + "] " + parts.get(i)
                            : "[" + code + "] " + parts.get(i));
                }
            }
            if (!rejectedNotes.isEmpty()) {
                out.add("以下操作未生成确认单：" + String.join("；", rejectedNotes));
            }
            out.add(instruction);
            chunks = out;
            promptText = instruction + "\n（完整计划已分 " + out.size() + " 条消息发送）";
        } else {
            StringBuilder sb = new StringBuilder("待确认 ");
            if (!multi) {
                WriteProposal only = finalWrites.get(0);
                sb.append('[').append(codes.get(only)).append("]：\n")
                        .append(String.join("", plans.get(only))).append("\n\n");
            } else {
                sb.append(created.size()).append(" 个操作：\n");
                for (WriteProposal w : finalWrites) {
                    sb.append('[').append(codes.get(w)).append("] ")
                            .append(w.tool().spec().title()).append('：')
                            .append(oneLine(String.join("", plans.get(w)), 200)).append('\n');
                }
                sb.append('\n');
            }
            if (!rejectedNotes.isEmpty()) {
                sb.append("以下操作未生成确认单：").append(String.join("；", rejectedNotes)).append('\n');
            }
            sb.append(instruction);
            promptText = sb.toString();
        }

        run.turn.setStatus(CopilotTurnEntity.PENDING_CONFIRM);
        run.turn.setAssistantText(CopilotJson.maskText(CopilotJson.truncate(promptText, 8000)));
        run.turn.setDurationMs(Duration.between(run.startedAt, Instant.now()).toMillis());
        turns.save(run.turn);
        saveMessage(run.conv.getId(), "assistant", promptText);

        return chunks.isEmpty() ? AgentReply.of(promptText, card) : AgentReply.chunked(card, chunks);
    }

    private AgentCard confirmationCard(List<WriteProposal> writes, Map<WriteProposal, String> codes,
                                       Map<WriteProposal, List<String>> plans,
                                       Map<WriteProposal, CopilotActionEntity> actionByProposal) {
        WriteProposal first = writes.get(0);
        boolean multi = writes.size() > 1;
        boolean dangerous = writes.stream().anyMatch(w -> w.risk() == ToolRisk.DANGEROUS);
        String title = multi ? "待确认（" + writes.size() + " 个操作）"
                : "待确认 [" + codes.get(first) + "] " + first.tool().spec().title();
        StringBuilder md = new StringBuilder();
        List<AgentCardButton> buttons = new ArrayList<>();
        for (WriteProposal w : writes) {
            String code = codes.get(w);
            String full = String.join("", plans.get(w));
            md.append("**[").append(code).append("] ").append(w.tool().spec().title()).append("**\n");
            md.append(w.risk() == ToolRisk.DANGEROUS ? "风险：危险操作（仅支持文本短码确认）\n" : "");
            md.append(CopilotJson.truncate(full, multi ? 500 : 1200));
            if (full.length() > (multi ? 500 : 1200)) {
                md.append("\n…完整计划已分 ").append(plans.get(w).size()).append(" 条文本发送");
            }
            md.append("\n\n");
            String actionId = actionByProposal.get(w).getId();
            if (w.risk() != ToolRisk.DANGEROUS) {
                buttons.add(AgentCardButton.confirm("确认 " + code, actionId));
            }
            buttons.add(AgentCardButton.cancel("取消 " + code, actionId));
        }
        if (dangerous) {
            md.append("危险操作请回复：确认 <完整短码>（卡片按钮不作为执行途径）。\n");
        }
        md.append("短码有效期 ").append(props.getConfirmTtlMinutes()).append(" 分钟。");
        return new AgentCard(title, dangerous ? AgentCard.DANGER : AgentCard.WARNING,
                md.toString(), buttons, "验证码/短码确认仅对发起人本人有效");
    }

    private String uniquePrefix(Run run) {
        for (int i = 0; i < 8; i++) {
            String code = CopilotIds.newShortCode();
            boolean used = actions.findInScope(run.req.channel(), run.req.externalChatId(),
                    run.req.senderId(), code).isPresent()
                    || actions.isPrefixOnly(run.req.channel(), run.req.externalChatId(),
                    run.req.senderId(), code);
            if (!used) {
                return code;
            }
        }
        return CopilotIds.newShortCode();
    }

    private void auditRejected(Run run, ToolCall call, String message) {
        auditToolCall(run, call, call.name(), call.args(), ToolRisk.READ,
                CopilotToolCallEntity.REJECTED, null, message, 0);
    }

    private void auditToolCall(Run run, ToolCall call, String toolName, Map<String, Object> args,
                               ToolRisk risk, String status, String summary, String error, long durationMs) {
        try {
            CopilotToolCallEntity row = new CopilotToolCallEntity();
            row.setTurnId(run.turn.getId());
            row.setToolName(toolName);
            row.setArgsJson(CopilotJson.maskedArgsJson(args, props.getMaxToolResultChars()));
            row.setRisk(risk == null ? ToolRisk.READ.name() : risk.name());
            row.setStatus(status);
            row.setResultSummary(CopilotJson.truncate(CopilotJson.maskText(summary), props.getMaxToolResultChars()));
            row.setError(CopilotJson.truncate(error, 2000));
            row.setDurationMs(durationMs);
            toolCalls.save(row);
        } catch (Exception e) {
            log.warn("智能体工具调用审计落库失败: turn={} tool={} err={}",
                    run.turn.getId(), toolName, e.getMessage());
        }
    }

    private void appendToolMessage(Run run, ToolCall call, String content) {
        String safe = CopilotJson.truncate(CopilotJson.maskText(content), props.getMaxToolResultChars());
        int index = run.messages.size();
        if ("json".equals(run.mode)) {
            run.messages.add(Message.user("工具 " + call.name() + " 的结果：\n" + safe));
        } else {
            run.messages.add(Message.tool(call.callId(), safe));
        }
        run.toolEntries.addLast(new ToolEntry(index, safe.length()));
        run.toolChars += safe.length();
        trimToolContext(run);
    }

    private void trimToolContext(Run run) {
        while (run.toolChars > props.getToolContextCharBudget() && !run.toolEntries.isEmpty()) {
            ToolEntry entry = run.toolEntries.pollFirst();
            if (entry.index() >= run.messages.size()) {
                continue;
            }
            Message old = run.messages.get(entry.index());
            run.messages.set(entry.index(),
                    new Message(old.role(), "[结果已省略]", old.toolCalls(), old.toolCallId()));
            run.toolChars -= entry.length();
        }
    }

    private void resetMessages(Run run, String mode) {
        run.messages.clear();
        run.toolEntries.clear();
        run.toolChars = 0;
        run.messages.add(Message.system(systemPrompt(run, mode)));
        for (Message message : history(run.conv.getId(), runTextSafe(run))) {
            run.messages.add(message);
        }
        run.messages.add(Message.user(run.userText));
    }

    private List<Message> history(String conversationId, String currentUserText) {
        List<CopilotMessageEntity> recent = messages
                .findTop100ByConversationIdOrderBySeqDesc(conversationId);
        Collections.reverse(recent);
        int budget = Math.max(1000, props.getHistoryCharBudget());
        int total = 0;
        int start = recent.size();
        for (int i = recent.size() - 1; i >= 0; i--) {
            int len = recent.get(i).getContent() == null ? 0 : recent.get(i).getContent().length();
            if (total + len > budget) {
                break;
            }
            total += len;
            start = i;
        }
        List<Message> out = new ArrayList<>();
        for (int i = start; i < recent.size(); i++) {
            CopilotMessageEntity m = recent.get(i);
            String content = m.getContent() == null ? "" : m.getContent();
            if ("user".equals(m.getRole())) {
                out.add(Message.user(content));
            } else if ("assistant".equals(m.getRole())) {
                out.add(Message.assistant(sanitizeHistoryContent(content)));
            }
        }
        // 本轮用户消息在 turn 创建时已入历史表，这里去掉最后一条自身，避免重复
        if (!out.isEmpty()) {
            Message last = out.get(out.size() - 1);
            if ("user".equals(last.role()) && last.content().equals(currentUserText)) {
                out.remove(out.size() - 1);
            }
        }
        return out;
    }

    /** 文本是否长得像平台确认单（「待确认 [短码]」「回复「确认 …」」等下发格式）。 */
    static boolean looksLikeConfirmation(String text) {
        return text != null && CONFIRM_FORMAT.matcher(text).find();
    }

    /** 历史里的确认单原文替换为无格式占位，防模型模仿编造假确认单（真机事故：假短码确认查无此单）。 */
    static String sanitizeHistoryContent(String content) {
        return looksLikeConfirmation(content) ? CONFIRM_PLACEHOLDER : content;
    }

    private String runTextSafe(Run run) {
        return CopilotJson.truncate(CopilotJson.maskText(run.userText), 4000);
    }

    private String systemPrompt(Run run, String mode) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 Dev-Mind 研发平台的智能体助手，通过聊天工具帮用户查询并操作研发平台。\n");
        sb.append("当前用户：").append(nullSafe(run.identity.actor()))
                .append("（角色 ").append(run.identity.role()).append("）\n");
        if (!isBlank(run.req.projectId())) {
            sb.append("当前默认项目：").append(isBlank(run.req.projectName())
                    ? run.req.projectId() : run.req.projectName() + "（" + run.req.projectId() + "）").append('\n');
        } else {
            sb.append("当前没有绑定项目。\n");
        }
        sb.append("今天日期：").append(LocalDate.now()).append("\n\n");
        sb.append("规则：\n");
        sb.append("- 只使用下方工具；需要跨项目查找时先调用 list_projects，禁止猜测或编造项目/对象 id。\n");
        sb.append("- 写操作会在平台生成确认单，必须由用户本人确认后才会执行；不要声称已经执行。\n");
        sb.append("- 确认单与短码由平台在你调用写操作工具后自动生成并下发；禁止自己编写「待确认 […]」"
                + "样式的文本，禁止编造短码或让用户回复确认。\n");
        sb.append("- 工具失败时如实转述原因，不要伪造结果；不要透露密钥、内部 URL、堆栈。\n");
        sb.append("- 回答使用简体中文，简洁、可操作；必要时给出下一步建议。\n\n");
        sb.append("可用工具：\n");
        for (PlatformTool tool : registry.visibleTo(run.identity.role())) {
            ToolSpec spec = tool.spec();
            sb.append("- ").append(spec.name()).append("（").append(spec.risk()).append("）：")
                    .append(oneLine(spec.description(), 300)).append('\n');
            if ("json".equals(mode)) {
                sb.append("  参数 schema：").append(oneLine(spec.parametersSchema(), 1200)).append('\n');
            }
        }
        if ("json".equals(mode)) {
            sb.append("\n本端不支持原生 tool calling。每次只输出一个 JSON 对象，不要输出 Markdown 代码围栏或其它文字：\n");
            sb.append("{\"tool\":\"工具名\",\"args\":{...}}  或  {\"answer\":\"最终回答\"}\n");
        }
        return sb.toString();
    }

    private List<ToolDef> toolDefs(String role) {
        List<ToolDef> out = new ArrayList<>();
        for (PlatformTool tool : registry.visibleTo(role)) {
            ToolSpec spec = tool.spec();
            out.add(new ToolDef(spec.name(), spec.description(), spec.parametersSchema()));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 结果收口
    // ------------------------------------------------------------------

    /**
     * 幻觉确认单护栏（FR-13 配套）：模型未调用写工具却输出确认单格式文本（编造短码）。
     * 存在真实确认单（本轮已建或 scope 内有 PENDING）时不拦——模型可能只是在指引用户确认既有单。
     *
     * @return 非 null = 终止回复（正常回答或护栏失败）；null = 已追加校正消息，调用方继续循环重试
     */
    private AgentReply guardHallucinatedConfirmation(Run run, String assistantRaw, String answer) {
        boolean faked = looksLikeConfirmation(answer)
                && actionRepo.findByTurnIdOrderByCreatedAtAsc(run.turn.getId()).isEmpty()
                && actions.pendingInScope(run.req.channel(), run.req.externalChatId(),
                        run.req.senderId()).isEmpty();
        if (!faked) {
            return finishAnswer(run, answer);
        }
        run.confirmHallucinations++;
        if (run.confirmHallucinations > 1) {
            log.warn("模型二次输出幻觉确认单，终止本轮: turn={} model={}", run.turn.getId(), run.turn.getModel());
            return failTurn(run, "模型未能正确发起写操作（输出了无效确认单），请换个说法重试，或直接在平台操作");
        }
        log.warn("模型输出幻觉确认单（未调用写工具），校正重试: turn={}", run.turn.getId());
        run.messages.add(Message.assistant(assistantRaw));
        run.messages.add(Message.user("系统校正：你刚才输出了「待确认」样式的确认单文本，但并未调用任何写操作工具。"
                + "确认单与短码由平台在工具调用后自动生成，禁止手写编造。"
                + "请重新处理用户请求：要么调用对应工具，要么如实回答。"));
        return null;
    }

    private AgentReply finishAnswer(Run run, String rawContent) {
        String text = CopilotJson.maskText(rawContent == null ? "" : rawContent.trim());
        if (text.isEmpty()) {
            text = "（模型没有返回内容，请换个问法试试）";
        }
        if (!run.links.isEmpty()) {
            StringBuilder links = new StringBuilder("\n\n相关链接：\n");
            int n = 0;
            for (String link : run.links) {
                links.append("- ").append(link).append('\n');
                if (++n >= MAX_LINKS) {
                    break;
                }
            }
            text = text + links;
        }
        run.turn.setStatus(CopilotTurnEntity.OK);
        run.turn.setAssistantText(CopilotJson.maskText(CopilotJson.truncate(text, 8000)));
        run.turn.setDurationMs(Duration.between(run.startedAt, Instant.now()).toMillis());
        turns.save(run.turn);
        saveMessage(run.conv.getId(), "assistant", CopilotJson.maskText(CopilotJson.truncate(text, 4000)));
        return AgentReply.of(text, answerCard(run, text));
    }

    private AgentCard answerCard(Run run, String text) {
        List<AgentCardButton> buttons = new ArrayList<>();
        for (String link : run.links) {
            buttons.add(AgentCardButton.link("打开链接", link));
            if (buttons.size() >= 3) {
                break;
            }
        }
        String footer = nullSafe(run.turn.getModel()) + " · " + run.steps + " 步 · "
                + Duration.between(run.startedAt, Instant.now()).toMillis() + "ms";
        return new AgentCard("Dev-Mind 智能体", AgentCard.INFO, text, buttons, footer);
    }

    private AgentReply finishMaxSteps(Run run) {
        String message = "已执行 " + run.steps + " 步仍未得到最终结果，请缩小范围、补充条件，或直接到平台查看。";
        return failTurn(run, message, CopilotTurnEntity.FAILED);
    }

    private AgentReply failTurn(Run run, String message) {
        return failTurn(run, message, CopilotTurnEntity.FAILED);
    }

    private AgentReply failTurn(Run run, String message, String status) {
        String safe = CopilotJson.maskText(message);
        run.turn.setStatus(status);
        run.turn.setError(CopilotJson.truncate(safe, 2000));
        run.turn.setAssistantText(CopilotJson.truncate(safe, 8000));
        run.turn.setDurationMs(Duration.between(run.startedAt, Instant.now()).toMillis());
        try {
            turns.save(run.turn);
        } catch (Exception e) {
            log.warn("智能体 turn 落库失败: turn={} err={}", run.turn.getId(), e.getMessage());
        }
        return AgentReply.text(safe);
    }

    private void collectLink(Run run, ToolResult result) {
        if (result == null || result.data() == null) {
            return;
        }
        Object link = result.data().get("link");
        if (link instanceof String s && !s.isBlank() && run.links.size() < MAX_LINKS) {
            run.links.add(s);
        }
    }

    private List<AgentCardButton> linkButtons(ToolResult result) {
        if (result == null || result.data() == null) {
            return List.of();
        }
        Object link = result.data().get("link");
        if (link instanceof String s && !s.isBlank()) {
            return List.of(AgentCardButton.link("查看详情", s));
        }
        return List.of();
    }

    // ------------------------------------------------------------------
    // 持久化与工具方法
    // ------------------------------------------------------------------

    private CopilotConversationEntity conversationOf(String channel, String chatId, String senderId,
                                                     String actor, String projectId) {
        CopilotConversationEntity conv = conversations
                .findByChannelAndExternalChatIdAndSenderId(channel, chatId, senderId)
                .orElseGet(() -> {
                    CopilotConversationEntity created = new CopilotConversationEntity();
                    created.setId(CopilotIds.newId());
                    created.setChannel(channel);
                    created.setExternalChatId(chatId);
                    created.setSenderId(senderId);
                    return created;
                });
        if (!isBlank(actor)) {
            conv.setActor(actor);
        }
        if (!isBlank(projectId)) {
            conv.setProjectId(projectId);
        }
        conv.setStatus("ACTIVE");
        conv.setLastTurnAt(Instant.now());
        return conversations.save(conv);
    }

    private CopilotTurnEntity newTurn(String conversationId, String channel, String chatId, String senderId,
                                      String actor, String projectId, String triggerType, String userText) {
        CopilotTurnEntity turn = new CopilotTurnEntity();
        turn.setId(CopilotIds.newId());
        turn.setConversationId(conversationId);
        turn.setChannel(channel);
        turn.setExternalChatId(chatId);
        turn.setSenderId(senderId);
        turn.setActor(actor);
        turn.setProjectId(projectId);
        turn.setTriggerType(triggerType);
        turn.setUserText(CopilotJson.truncate(userText, 4000));
        turn.setStatus(CopilotTurnEntity.OK);
        return turns.save(turn);
    }

    private void saveMessage(String conversationId, String role, String content) {
        CopilotMessageEntity message = new CopilotMessageEntity();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content == null ? "" : content);
        message.setSeq(nextSeq(conversationId));
        message.setCreatedAt(Instant.now());
        messages.save(message);
    }

    private long nextSeq(String conversationId) {
        return messages.findTop100ByConversationIdOrderBySeqDesc(conversationId).stream()
                .findFirst().map(m -> m.getSeq() + 1).orElse(0L);
    }

    private ToolContext toolContext(Run run, String actionId, String projectId) {
        return new ToolContext(run.turn.getId(), actionId, run.identity.actor(), run.identity.role(),
                run.req.channel(), run.req.externalChatId(), run.req.senderId(), run.req.personalActor(),
                projectId, run.req.projectName(), run.startedAt);
    }

    private String resolveProject(PlatformTool tool, Map<String, Object> args, Run run) {
        String resolved = tool.resolveProjectId(args, toolContext(run, null, run.req.projectId()));
        return isBlank(resolved) ? run.req.projectId() : resolved;
    }

    private boolean rateLimited(String actor) {
        if (isBlank(actor)) {
            return false;
        }
        return turns.countByActorAndCreatedAtAfter(actor, Instant.now().minusSeconds(60))
                >= Math.max(1, props.getMaxTurnsPerMinute());
    }

    private ReentrantLock lockFor(String channel, String chatId, String senderId) {
        return locks.computeIfAbsent(scopeKey(channel, chatId, senderId), k -> new ReentrantLock());
    }

    private static String scopeKey(String channel, String chatId, String senderId) {
        return channel + "\u0001" + chatId + "\u0001" + senderId;
    }

    private static String missingScope(String channel, String chatId, String senderId) {
        if (isBlank(channel) || isBlank(chatId) || isBlank(senderId)) {
            return "缺少会话上下文（channel/chat/sender），无法处理";
        }
        return null;
    }

    private static String pendingList(List<CopilotActionEntity> pending, String prefix) {
        StringBuilder sb = new StringBuilder(prefix).append('\n');
        for (CopilotActionEntity a : pending) {
            sb.append("- ").append(a.getShortCode()).append("（").append(a.getRisk()).append("）")
                    .append(titleOfPending(a)).append('\n');
        }
        return sb.toString().trim();
    }

    private static String titleOfPending(CopilotActionEntity action) {
        String preview = action.getPreviewText();
        return oneLine(preview == null ? action.getToolName() : preview, 120);
    }

    private static String oneLine(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    private static String errorText(String message) {
        return "[错误] " + (message == null ? "未知原因" : message);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /** 预览切块：按字符数切（保持顺序），不截断内容。 */
    static List<String> splitChunks(String text, int size) {
        List<String> parts = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            parts.add("");
            return parts;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + size);
            // 不在代理对中间切断
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
                end = Math.max(start + 1, end - 1);
            }
            parts.add(text.substring(start, end));
            start = end;
        }
        return parts;
    }

    private JsonDecision parseDecision(String content) {
        String text = content == null ? "" : content.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int fence = text.lastIndexOf("```");
            if (firstNewline >= 0 && fence > firstNewline) {
                text = text.substring(firstNewline + 1, fence).trim();
            }
        }
        int open = text.indexOf('{');
        int close = text.lastIndexOf('}');
        if (open >= 0 && close > open) {
            text = text.substring(open, close + 1);
        }
        JsonNode node = mapper.readTree(text);
        if (!node.isObject()) {
            throw new IllegalArgumentException("not an object");
        }
        if (node.hasNonNull("answer")) {
            return new JsonDecision(null, Map.of(), node.path("answer").asText(""));
        }
        if (node.hasNonNull("tool")) {
            String name = node.path("tool").asText("").trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("missing tool");
            }
            Map<String, Object> args = new LinkedHashMap<>();
            JsonNode argsNode = node.path("args");
            if (argsNode.isObject()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> converted = mapper.convertValue(argsNode, Map.class);
                args.putAll(converted);
            }
            return new JsonDecision(name, args, null);
        }
        throw new IllegalArgumentException("missing tool/answer");
    }

    // ------------------------------------------------------------------
    // 内部结构
    // ------------------------------------------------------------------

    private record JsonDecision(String tool, Map<String, Object> args, String answer) {
    }

    private record WriteProposal(PlatformTool tool, ToolCall call, Map<String, Object> args,
                                 ToolRisk risk, String projectId) {
    }

    private record ToolEntry(int index, int length) {
    }

    private final class Run {
        final AgentRequest req;
        final CopilotIdentityService.Identity identity;
        final CopilotConversationEntity conv;
        final CopilotTurnEntity turn;
        final String userText;
        final Instant startedAt = Instant.now();
        final List<Message> messages = new ArrayList<>();
        final Deque<ToolEntry> toolEntries = new ArrayDeque<>();
        final Map<String, Integer> callCounts = new HashMap<>();
        final Set<String> links = new LinkedHashSet<>();
        Instant deadline;
        int toolChars;
        int steps;
        int promptTokens;
        int completionTokens;
        int confirmHallucinations;
        String mode = "native";

        Run(AgentRequest req, CopilotIdentityService.Identity identity,
            CopilotConversationEntity conv, CopilotTurnEntity turn, String userText) {
            this.req = req;
            this.identity = identity;
            this.conv = conv;
            this.turn = turn;
            this.userText = userText;
        }
    }
}
