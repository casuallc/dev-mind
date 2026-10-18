package com.devmind.common.agent.gateway;

import java.util.List;

/**
 * 中性卡片（CAP-76 FR-25）：不含任何平台专有 JSON，渲染职责在渠道适配器。
 *
 * @param title    标题
 * @param template INFO / SUCCESS / WARNING / DANGER
 * @param markdown 正文（飞书适配器按支持的 Markdown 子集渲染）
 * @param buttons  按钮（可空）
 * @param footer   脚注（可空）
 */
public record AgentCard(
        String title,
        String template,
        String markdown,
        List<AgentCardButton> buttons,
        String footer) {

    public static final String INFO = "INFO";
    public static final String SUCCESS = "SUCCESS";
    public static final String WARNING = "WARNING";
    public static final String DANGER = "DANGER";

    public AgentCard {
        template = template == null || template.isBlank() ? INFO : template;
        buttons = buttons == null ? List.of() : List.copyOf(buttons);
        markdown = markdown == null ? "" : markdown;
    }

    public static AgentCard of(String title, String template, String markdown) {
        return new AgentCard(title, template, markdown, List.of(), null);
    }

    /** 纯文本渠道降级：标题 + 正文 + 链接 + 脚注（确认短码已在正文里）。 */
    public String toPlainText() {
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) {
            sb.append("【").append(title).append("】\n");
        }
        if (!markdown.isBlank()) {
            sb.append(markdown.trim()).append('\n');
        }
        for (AgentCardButton button : buttons) {
            if (button.isLink() && button.url() != null && !button.url().isBlank()) {
                sb.append("- ").append(button.label()).append(": ").append(button.url()).append('\n');
            }
        }
        if (footer != null && !footer.isBlank()) {
            sb.append(footer.trim()).append('\n');
        }
        return sb.toString().trim();
    }
}
