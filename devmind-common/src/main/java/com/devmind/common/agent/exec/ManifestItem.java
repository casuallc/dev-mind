package com.devmind.common.agent.exec;

/**
 * CAP-33 FR-07 已注入上下文清单条目：装配时由 {@link ContextProvider} 逐项产出
 * （含来源标注），assembler 汇总为 context_manifest_json 快照落库，
 * 会话/问答详情「已注入上下文」直接读快照展示，本机/远程口径一致。
 *
 * @param kind   资产类型：{@link #KIND_KNOWLEDGE} / {@link #KIND_SKILL} / {@link #KIND_DOC}
 * @param ref    资产主键（knowledge/doc 为数值 id 串，skill 为 UUID）
 * @param name   展示名（知识条目名 / skill name / 文档标题）
 * @param scope  资产 scope（GLOBAL/PROJECT/global/project；无 scope 概念的资产为 null）
 * @param source 注入来源：{@link #SOURCE_SCENARIO} / {@link #SOURCE_PROJECT_AUTO} / {@link #SOURCE_REQUEST}
 * @param extra  类型相关的附加信息（如 doc 的 version/path、skill 的文件数），可空 map
 */
public record ManifestItem(String kind, String ref, String name, String scope, String source,
                           java.util.Map<String, Object> extra) {

    public static final String KIND_KNOWLEDGE = "knowledge";
    public static final String KIND_SKILL = "skill";
    public static final String KIND_DOC = "doc";

    /** 场景显式绑定（FR-02 ①层）。 */
    public static final String SOURCE_SCENARIO = "scenario";
    /** 项目自动命中（FR-02 ②层，由 provider 自行识别标注）。 */
    public static final String SOURCE_PROJECT_AUTO = "project-auto";
    /** 请求级追加（FR-02 ③层）。 */
    public static final String SOURCE_REQUEST = "request";
}
