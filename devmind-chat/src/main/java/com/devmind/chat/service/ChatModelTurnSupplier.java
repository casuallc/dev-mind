package com.devmind.chat.service;

import com.devmind.chat.model.ChatEventEntity;
import com.devmind.chat.repo.ChatEventRepository;
import com.devmind.common.agent.SessionEvent;
import com.devmind.common.agent.runtime.ModelSessionRuntime;
import com.devmind.common.model.OpenAiCompatChatStream;
import org.springframework.data.domain.PageRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * CAP-49 模型执行体的多轮装配：{@code system}（固定提示 + 可选库概览）→ 历史 → 本轮 user。
 *
 * <p><b>对话状态不自己存</b>：每次提问都从 chat_events 重建上下文，所以服务端无内存态——
 * 这正是模型执行体比 CLI 会话多出来的收益（重启后仍可继续提问）。</p>
 *
 * <h2>几处不能想当然的地方</h2>
 * <ul>
 *   <li><b>两个来源按 seq 归并</b>：事件走 200ms 批量落库，用户紧接着上一轮提问时，上一轮的
 *       assistant 还在内存环形缓冲里没进 DB。只读 DB 会丢最近一轮，只读内存则重启后只剩空历史。</li>
 *   <li><b>注入前缀必须剥掉</b>：历轮用户消息里带着 {@code <knowledge-context>} 检索块、
 *       首轮还带 {@code <knowledge-base>} 库概览（CAP-46）——原样回灌等于把旧检索结果当新资料，
 *       既浪费 token 又误导模型。本轮新检索块在 {@code userText} 里，不经过这里。</li>
 *   <li><b>连续 assistant 合并</b>：一轮被中断/失败会留下多段 assistant（部分正文 + 后续补答），
 *       与前端同一规则用 {@code \n\n} 合并，否则模型看到多个连续 assistant 会当成多轮。</li>
 *   <li><b>历史必须以 user 开头</b>：按字符预算从尾部裁掉最老的一段后，开头可能剩一条 assistant，
 *       丢弃到第一个 user 为止（聊天 API 普遍要求 user/assistant 交替起始）。</li>
 * </ul>
 */
public class ChatModelTurnSupplier implements ModelSessionRuntime.TurnSupplier {

    /** 参与装配的最大事件数（DB 查询页大小 + 归并后上限）：够长又不会把请求撑爆 */
    static final int MAX_HISTORY_EVENTS = 400;
    /** 历史字符预算（约 24k 字符 ≈ 12k token 量级，给本轮提问与回答留足余量） */
    static final int HISTORY_CHAR_BUDGET = 24_000;

    private static final String USER = "user";
    private static final String ASSISTANT = "assistant";
    /** 注入块的标签（剥离顺序无关，两个都试） */
    private static final List<String> INJECT_TAGS = List.of("knowledge-base", "knowledge-context");

    private final String chatId;
    private final String systemPrompt;
    private final ChatEventRepository eventRepo;

    /** 归并用的最小事件视图：装配只关心"谁说的、说了什么" */
    private record Turn(String type, String content) {
    }

    public ChatModelTurnSupplier(String chatId, String systemPrompt, ChatEventRepository eventRepo) {
        this.chatId = chatId;
        this.systemPrompt = systemPrompt;
        this.eventRepo = eventRepo;
    }

    @Override
    public List<OpenAiCompatChatStream.Message> buildTurn(String userText, long beforeSeq,
                                                          List<SessionEvent> recent) {
        List<OpenAiCompatChatStream.Message> out = new ArrayList<>();
        out.add(OpenAiCompatChatStream.Message.system(systemPrompt));
        out.addAll(history(beforeSeq, recent));
        out.add(OpenAiCompatChatStream.Message.user(userText));
        return out;
    }

    /** 历史消息（不含 system 与本轮提问），按时间正序。 */
    private List<OpenAiCompatChatStream.Message> history(long beforeSeq, List<SessionEvent> recent) {
        TreeMap<Long, Turn> bySeq = new TreeMap<>();
        for (ChatEventEntity row : eventRepo.findByChatIdAndSeqLessThanOrderBySeqDesc(
                chatId, beforeSeq, PageRequest.of(0, MAX_HISTORY_EVENTS))) {
            bySeq.put(row.getSeq(), new Turn(row.getType(), row.getContent()));
        }
        if (recent != null) {
            for (SessionEvent ev : recent) {
                // 同 seq 以内存为准：内存里的更完整（DB 那条可能还没写）
                if (ev.seq() < beforeSeq) {
                    bySeq.put(ev.seq(), new Turn(ev.type(), ev.content()));
                }
            }
        }
        while (bySeq.size() > MAX_HISTORY_EVENTS) {
            bySeq.pollFirstEntry();
        }

        List<OpenAiCompatChatStream.Message> msgs = new ArrayList<>();
        for (Turn t : bySeq.values()) {
            String role = roleOf(t.type());
            if (role == null) {
                continue;
            }
            String text = stripInjected(t.content());
            if (text.isBlank()) {
                continue;
            }
            if (ASSISTANT.equals(role) && !msgs.isEmpty()
                    && ASSISTANT.equals(msgs.get(msgs.size() - 1).role())) {
                OpenAiCompatChatStream.Message prev = msgs.remove(msgs.size() - 1);
                text = prev.content() + "\n\n" + text;
            }
            msgs.add(new OpenAiCompatChatStream.Message(role, text));
        }
        dropLeadingAssistant(msgs);
        return withinBudget(msgs);
    }

    /** 只取一问一答：tool_use/state/log/error/result/text_delta 都不是对话内容。 */
    private static String roleOf(String type) {
        return switch (type == null ? "" : type) {
            case USER -> USER;
            case ASSISTANT -> ASSISTANT;
            default -> null;
        };
    }

    /**
     * 剥掉开头的注入块（可叠加：首轮 {@code <knowledge-base>} + 每轮 {@code <knowledge-context>}）。
     * 没有注入块时原样返回（strip 过的文本）。
     */
    static String stripInjected(String content) {
        if (content == null) {
            return "";
        }
        String s = content.strip();
        while (true) {
            String next = stripOneBlock(s);
            if (next == null) {
                return s;
            }
            s = next.strip();
        }
    }

    private static String stripOneBlock(String s) {
        for (String tag : INJECT_TAGS) {
            String open = "<" + tag + ">";
            if (s.startsWith(open)) {
                int end = s.indexOf("</" + tag + ">");
                if (end >= 0) {
                    return s.substring(end + tag.length() + 3);
                }
            }
        }
        return null;
    }

    private static void dropLeadingAssistant(List<OpenAiCompatChatStream.Message> msgs) {
        while (!msgs.isEmpty() && ASSISTANT.equals(msgs.get(0).role())) {
            msgs.remove(0);
        }
    }

    /** 从尾部按字符预算裁剪（至少保留最新一条，哪怕它自己就超预算）。 */
    private static List<OpenAiCompatChatStream.Message> withinBudget(
            List<OpenAiCompatChatStream.Message> msgs) {
        int from = msgs.size();
        int total = 0;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            int len = msgs.get(i).content().length();
            if (total + len > HISTORY_CHAR_BUDGET && from < msgs.size()) {
                break;
            }
            total += len;
            from = i;
        }
        List<OpenAiCompatChatStream.Message> kept = new ArrayList<>(msgs.subList(from, msgs.size()));
        dropLeadingAssistant(kept);
        return kept;
    }
}
