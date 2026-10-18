package com.devmind.common.agent.gateway;

/**
 * 中性卡片按钮（CAP-76 FR-25）。
 *
 * @param label     按钮文案
 * @param operation CONFIRM / CANCEL / LINK
 * @param actionId  确认单 id（LINK 时为空）
 * @param url       链接地址（LINK 时必填）
 */
public record AgentCardButton(String label, String operation, String actionId, String url) {

    public static final String CONFIRM = "CONFIRM";
    public static final String CANCEL = "CANCEL";
    public static final String LINK = "LINK";

    public static AgentCardButton confirm(String label, String actionId) {
        return new AgentCardButton(label, CONFIRM, actionId, null);
    }

    public static AgentCardButton cancel(String label, String actionId) {
        return new AgentCardButton(label, CANCEL, actionId, null);
    }

    public static AgentCardButton link(String label, String url) {
        return new AgentCardButton(label, LINK, null, url);
    }

    public boolean isLink() {
        return LINK.equals(operation);
    }
}
