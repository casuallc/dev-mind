package com.devmind.common.agent.tool;

/**
 * 工具风险级别（CAP-76 FR-02）：
 *
 * <ul>
 *   <li>{@link #READ}：只读，直接执行；</li>
 *   <li>{@link #WRITE}：会改业务数据，必须先落确认单并由本人确认；</li>
 *   <li>{@link #DANGEROUS}：等于让 runner/coding agent 执行输入或不可逆状态流转，
 *       必须用文本短码确认，卡片不提供执行按钮。</li>
 * </ul>
 */
public enum ToolRisk {
    READ,
    WRITE,
    DANGEROUS;

    /** 写操作（WRITE 或 DANGEROUS）才需要确认单。 */
    public boolean writes() {
        return this != READ;
    }
}
