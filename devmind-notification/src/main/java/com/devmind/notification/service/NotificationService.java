package com.devmind.notification.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.notification.NotificationEvent;
import com.devmind.notification.action.NotificationActionHandler;
import com.devmind.notification.channel.NotificationChannel;
import com.devmind.notification.config.NotificationProperties;
import com.devmind.notification.dto.ActionDef;
import com.devmind.notification.dto.ChannelRequest;
import com.devmind.notification.dto.ChannelView;
import com.devmind.notification.dto.EmitRequest;
import com.devmind.notification.dto.NotificationDraft;
import com.devmind.notification.dto.NotificationView;
import com.devmind.notification.dto.NotificationViews;
import com.devmind.notification.dto.PrefsRequest;
import com.devmind.notification.dto.PrefsView;
import com.devmind.notification.model.NotificationChannelEntity;
import com.devmind.notification.model.NotificationEntity;
import com.devmind.notification.model.NotificationLevel;
import com.devmind.notification.model.NotificationPrefsEntity;
import com.devmind.notification.NotificationPublisher;
import com.devmind.notification.repo.NotificationChannelRepository;
import com.devmind.notification.repo.NotificationPrefsRepository;
import com.devmind.notification.repo.NotificationRepository;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP-06 通知中心核心：事件路由→分级→去重→免打扰→分发通道；通知中心（未读/历史/动作）。
 *
 * <p>实现 {@link NotificationPublisher}——会话层只调 publish(NotificationEvent)，不关心通道。
 * 消息流：publish → draftFrom(事件路由) → emit(去重/持久化/分发)。</p>
 */
@Service
public class NotificationService implements NotificationPublisher {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final String LOCAL_USER = "local";
    private static final String ENTITY_SESSION = "SESSION";
    /** 站内中心通道：不走免打扰/静默过滤（中心是常驻，不是打扰）。 */
    private static final String CENTER_CODE = "ws";

    private final NotificationRepository repo;
    private final NotificationChannelRepository channelRepo;
    private final NotificationPrefsRepository prefsRepo;
    private final NotificationProperties props;
    private final ObjectMapper mapper;
    private final List<NotificationChannel> channels;
    /** 动作处理器用 ObjectProvider 延迟解析，避免与 session 模块形成启动期循环依赖。 */
    private final ObjectProvider<NotificationActionHandler> actionHandlers;

    public NotificationService(NotificationRepository repo,
                               NotificationChannelRepository channelRepo,
                               NotificationPrefsRepository prefsRepo,
                               NotificationProperties props,
                               ObjectMapper mapper,
                               List<NotificationChannel> channels,
                               ObjectProvider<NotificationActionHandler> actionHandlers) {
        this.repo = repo;
        this.channelRepo = channelRepo;
        this.prefsRepo = prefsRepo;
        this.props = props;
        this.mapper = mapper;
        this.channels = channels;
        this.actionHandlers = actionHandlers;
    }

    @PostConstruct
    void seed() {
        if (channelRepo.count() == 0) {
            seedChannel("ws", "站内（WebSocket）", true, NotificationLevel.P2, Map.of());
            seedChannel("log", "日志", true, NotificationLevel.P2, Map.of());
            seedChannel("bark", "Bark（iPhone 推送）", false, NotificationLevel.P0,
                    Map.of("server", "https://api.day.app", "key", ""));
            seedChannel("wecom", "企业微信 Webhook", false, NotificationLevel.P0,
                    Map.of("webhookUrl", ""));
            log.info("通知通道已初始化（ws/log/bark/wecom）");
        }
        if (prefsRepo.findById(LOCAL_USER).isEmpty()) {
            NotificationPrefsEntity p = new NotificationPrefsEntity();
            p.setUserId(LOCAL_USER);
            p.setMutesJson("{}");
            p.setQuietStart("");
            p.setQuietEnd("");
            prefsRepo.save(p);
        }
    }

    // ---------------- Publisher（会话层入口） ----------------

    @Override
    public void publish(NotificationEvent event) {
        try {
            emit(draftFrom(event));
        } catch (Exception e) {
            log.warn("通知发布失败: kind={} session={} err={}",
                    event.kind(), event.sessionId(), e.getMessage());
        }
    }

    /** 事件路由：kind → 级别 + 快捷动作（FR-02/FR-04）。 */
    private NotificationDraft draftFrom(NotificationEvent ev) {
        NotificationLevel level = switch (ev.kind()) {
            case "WAITING_AUTH", "WAITING_INPUT", "SESSION_FAILED", "INPUT_TIMEOUT" -> NotificationLevel.P0;
            case "SESSION_DONE" -> NotificationLevel.P1;
            default -> NotificationLevel.P2; // SESSION_STARTED 及未知
        };
        List<ActionDef> actions = switch (ev.kind()) {
            case "WAITING_AUTH" -> List.of(
                    new ActionDef("authorize", "允许授权"),
                    new ActionDef("deny", "拒绝"));
            case "WAITING_INPUT" -> List.of(new ActionDef("finish", "结束会话"));
            case "SESSION_DONE", "SESSION_FAILED", "SESSION_STARTED" -> List.of(new ActionDef("view", "查看会话"));
            default -> List.of();
        };
        return new NotificationDraft(level, ev.kind(), ev.title(), ev.content(),
                ENTITY_SESSION, ev.sessionId(), actions);
    }

    // ---------------- emit 核心 ----------------

    /** 发布一条完整通知：去重 → 持久化 → 分发通道 → 返回视图（去重跳过时返回 null）。 */
    public NotificationView emit(NotificationDraft draft) {
        if (draft.title() == null || draft.title().isBlank()) {
            return null;
        }
        if (draft.entityId() != null && !draft.entityId().isBlank()) {
            Instant cutoff = Instant.now().minus(Duration.ofMinutes(props.dedupMinutes()));
            long dup = repo.countByEventTypeAndEntityIdAndCreatedAtAfter(
                    draft.eventType(), draft.entityId(), cutoff);
            if (dup > 0) {
                log.info("去重忽略重复通知: {} entity={}（{}s 内）",
                        draft.eventType(), draft.entityId(), props.dedupMinutes() * 60);
                return null;
            }
        }
        NotificationEntity ent = new NotificationEntity();
        ent.setLevel(draft.level());
        ent.setEventType(draft.eventType());
        ent.setTitle(draft.title());
        ent.setBody(draft.body());
        ent.setEntityType(draft.entityType());
        ent.setEntityId(draft.entityId());
        ent.setActions(NotificationViews.toJson(mapper, draft.actions()));
        ent.setCreatedAt(Instant.now());
        ent = repo.save(ent);
        dispatch(ent, draft);
        return NotificationViews.toView(ent, mapper);
    }

    /** 通道分发：启用 → 阈值 → 免打扰 → 静默 → 发送；结果记录到 channelStatus。 */
    private void dispatch(NotificationEntity ent, NotificationDraft draft) {
        Map<String, String> status = new LinkedHashMap<>();
        PrefsView prefs = getPrefs();
        boolean quiet = inQuietHours(prefs.quietStart(), prefs.quietEnd(), Instant.now());
        for (NotificationChannelEntity ch : channelRepo.findAll()) {
            String st;
            try {
                if (!ch.isEnabled()) {
                    st = "SKIPPED:disabled";
                } else if (!isCenter(ch.getCode())
                        && !NotificationLevel.meets(draft.level().name(), ch.getLevelThreshold())) {
                    st = "SKIPPED:threshold(" + ch.getLevelThreshold() + ")";
                } else if (!isCenter(ch.getCode()) && quiet && draft.level() != NotificationLevel.P0) {
                    st = "SKIPPED:quiet-hours";
                } else if (!isCenter(ch.getCode()) && isMuted(prefs, draft.eventType(), draft.entityId())) {
                    st = "SKIPPED:muted";
                } else {
                    channelFor(ch.getCode()).send(ch, NotificationViews.toView(ent, mapper));
                    st = "SENT";
                }
            } catch (Exception e) {
                st = "FAILED:" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                log.warn("通道发送失败: code={} event={} err={}",
                        ch.getCode(), draft.eventType(), e.getMessage());
            }
            status.put(ch.getCode(), st);
        }
        ent.setChannelStatus(NotificationViews.toJson(mapper, status));
        repo.save(ent);
    }

    private boolean isCenter(String code) {
        return CENTER_CODE.equals(code);
    }

    private NotificationChannel channelFor(String code) {
        for (NotificationChannel c : channels) {
            if (c.code().equals(code)) {
                return c;
            }
        }
        throw new IllegalStateException("未注册的通道: " + code);
    }

    // ---------------- 通知中心 ----------------

    public List<NotificationView> list(String level, boolean unreadOnly, int limit) {
        List<NotificationEntity> all;
        if (level != null && !level.isBlank()) {
            all = unreadOnly
                    ? repo.findByLevelAndReadAtIsNullOrderByCreatedAtDesc(level)
                    : repo.findByLevelOrderByCreatedAtDesc(level);
        } else {
            all = unreadOnly
                    ? repo.findByReadAtIsNullOrderByCreatedAtDesc()
                    : repo.findByOrderByCreatedAtDesc();
        }
        if (limit > 0 && all.size() > limit) {
            all = all.subList(0, limit);
        }
        return all.stream().map(e -> NotificationViews.toView(e, mapper)).toList();
    }

    public long unreadCount() {
        return repo.countByReadAtIsNull();
    }

    @Transactional
    public void markRead(Long id) {
        repo.findById(id).ifPresent(e -> {
            e.setReadAt(Instant.now());
            repo.save(e);
        });
    }

    @Transactional
    public int markAllRead() {
        List<NotificationEntity> unread = repo.findByReadAtIsNullOrderByCreatedAtDesc();
        if (unread.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        unread.forEach(e -> e.setReadAt(now));
        repo.saveAll(unread);
        return unread.size();
    }

    /** 执行快捷动作（FR-04）：交给注册的 handler；"view" 由前端跳转，服务端仅标记已读。 */
    @Transactional
    public NotificationView action(Long id, String action) {
        NotificationEntity ent = repo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "通知不存在: " + id));
        if (action == null || action.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "缺少动作");
        }
        boolean handled = false;
        if (!"view".equals(action)) {
            for (NotificationActionHandler h : actionHandlers.orderedStream().toList()) {
                if (h.supports(ent.getEntityType()) && h.canHandle(action)) {
                    h.handle(ent.getEntityType(), ent.getEntityId(), action);
                    handled = true;
                    break;
                }
            }
            if (!handled) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "不支持的动作: " + action + " entityType=" + ent.getEntityType());
            }
        }
        ent.setReadAt(Instant.now());
        repo.save(ent);
        return NotificationViews.toView(ent, mapper);
    }

    /** 测试/调试 emit（临时端点）。 */
    public NotificationView emitTest(EmitRequest req) {
        NotificationLevel level = req.level() != null ? req.level() : NotificationLevel.P2;
        String eventType = req.eventType() != null && !req.eventType().isBlank() ? req.eventType() : "TEST_" + level;
        String entityId = req.entityId() != null && !req.entityId().isBlank()
                ? req.entityId() : "test-" + System.currentTimeMillis();
        return emit(new NotificationDraft(level, eventType,
                req.title() != null ? req.title() : "测试通知",
                req.body(), req.entityType() != null ? req.entityType() : "TEST",
                entityId, List.of()));
    }

    // ---------------- 通道配置 ----------------

    public List<ChannelView> listChannels() {
        return channelRepo.findAll().stream().map(this::toChannelView).toList();
    }

    @Transactional
    public ChannelView updateChannel(Long id, ChannelRequest req) {
        NotificationChannelEntity ent = channelRepo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "通道不存在: " + id));
        ent.setEnabled(req.enabled());
        if (req.levelThreshold() != null && !req.levelThreshold().isBlank()) {
            ent.setLevelThreshold(req.levelThreshold().toUpperCase());
        }
        if (req.config() != null) {
            Map<String, Object> merged = new LinkedHashMap<>(NotificationViews.parseJsonMap(mapper, ent.getConfigJson()));
            merged.putAll(req.config());
            ent.setConfigJson(NotificationViews.toJson(mapper, merged));
        }
        return toChannelView(channelRepo.save(ent));
    }

    private ChannelView toChannelView(NotificationChannelEntity e) {
        return new ChannelView(e.getId(), e.getCode(), e.getName(), e.isEnabled(),
                e.getLevelThreshold(), NotificationViews.parseJsonMap(mapper, e.getConfigJson()));
    }

    // ---------------- 偏好 ----------------

    public PrefsView getPrefs() {
        NotificationPrefsEntity e = prefsRepo.findById(LOCAL_USER).orElseGet(this::defaultPrefs);
        Map<String, List<String>> mutes = NotificationViews.parseJsonMap(mapper, e.getMutesJson())
                .entrySet().stream()
                .collect(LinkedHashMap::new,
                        (m, kv) -> m.put(kv.getKey(), NotificationViews.listFrom(mapper, jsonOf(kv.getValue()), String.class)),
                        LinkedHashMap::putAll);
        List<String> entityIds = mutes.getOrDefault("entityIds", List.of());
        List<String> eventTypes = mutes.getOrDefault("eventTypes", List.of());
        return new PrefsView(Map.of("eventTypes", eventTypes), e.getQuietStart(), e.getQuietEnd(), entityIds);
    }

    private String jsonOf(Object value) {
        return value instanceof String s ? s : NotificationViews.toJson(mapper, value);
    }

    @Transactional
    public PrefsView updatePrefs(PrefsRequest req) {
        NotificationPrefsEntity e = prefsRepo.findById(LOCAL_USER).orElseGet(this::defaultPrefs);
        if (req.mutes() != null && req.mutes().get("eventTypes") != null) {
            Map<String, Object> mutes = new LinkedHashMap<>(NotificationViews.parseJsonMap(mapper, e.getMutesJson()));
            mutes.put("eventTypes", req.mutes().get("eventTypes"));
            e.setMutesJson(NotificationViews.toJson(mapper, mutes));
        }
        if (req.perSessionSilence() != null) {
            Map<String, Object> mutes = new LinkedHashMap<>(NotificationViews.parseJsonMap(mapper, e.getMutesJson()));
            mutes.put("entityIds", req.perSessionSilence());
            e.setMutesJson(NotificationViews.toJson(mapper, mutes));
        }
        // 整体替换语义：null/空 = 清除
        e.setQuietStart(blankToNull(req.quietStart()));
        e.setQuietEnd(blankToNull(req.quietEnd()));
        prefsRepo.save(e);
        return getPrefs();
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private NotificationPrefsEntity defaultPrefs() {
        NotificationPrefsEntity p = new NotificationPrefsEntity();
        p.setUserId(LOCAL_USER);
        p.setMutesJson("{}");
        return p;
    }

    // ---------------- 过滤小工具 ----------------

    private boolean isMuted(PrefsView prefs, String eventType, String entityId) {
        for (String t : prefs.mutes().getOrDefault("eventTypes", List.of())) {
            if (t.equalsIgnoreCase(eventType)) {
                return true;
            }
        }
        for (String id : prefs.perSessionSilence()) {
            if (id != null && id.equalsIgnoreCase(entityId)) {
                return true;
            }
        }
        return false;
    }

    /** "HH:mm" 时段判断；start>=end 视为跨天（如 23:00~07:30）。 */
    static boolean inQuietHours(String start, String end, Instant now) {
        if (start == null || start.isBlank() || end == null || end.isBlank()) {
            return false;
        }
        int s = hm(start);
        int e = hm(end);
        if (s == e) {
            return false;
        }
        ZonedDateTime z = now.atZone(ZoneId.systemDefault());
        int n = z.getHour() * 60 + z.getMinute();
        return s < e ? (n >= s && n < e) : (n >= s || n < e);
    }

    private static int hm(String s) {
        try {
            String[] p = s.split(":");
            return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
        } catch (Exception ex) {
            return -1;
        }
    }

    private void seedChannel(String code, String name, boolean enabled,
                             NotificationLevel threshold, Map<String, Object> config) {
        NotificationChannelEntity e = new NotificationChannelEntity();
        e.setCode(code);
        e.setName(name);
        e.setEnabled(enabled);
        e.setLevelThreshold(threshold.name());
        e.setConfigJson(NotificationViews.toJson(mapper, config));
        channelRepo.save(e);
    }
}
