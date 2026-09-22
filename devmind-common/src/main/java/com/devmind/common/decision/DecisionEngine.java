package com.devmind.common.decision;

import java.util.Map;
import java.util.Optional;

/**
 * CAP-55 FR-03 决策引擎 SPI：把「类型化判断」这类 System 1 问题交给小决策模型
 * （laya 三原语：choice 选一项 / score 打分 / noul 给一个 0~1 的概率）。
 *
 * <p>实现方 {@code devmind-decision}（HTTP 客户端，走 CAP-48 的 {@code kind=DECISION} 端点），
 * 消费方（本 CAP 的 devmind-knowledge 提案分诊，后续 notification/session 等）
 * 以 {@code ObjectProvider<DecisionEngine>} <b>探测注入</b>：未装配 = 没有决策能力 =
 * 走纯人工路径，零反向依赖。</p>
 *
 * <h2>契约</h2>
 * <ul>
 *   <li><b>永不上抛</b>：任何失败（没配端点 / 边车超时 / 应答不可解析）都转成
 *       {@link DecisionResult#degraded}，由调用方按 FR-06 降级链处理。这条是硬性的——
 *       决策是"锦上添花"的能力，绝不能因为边车挂了把知识库的正常写入拖成 500。</li>
 *   <li><b>state 是给模型看的上下文快照</b>（提案正文、相似条目 …），值超长由实现方截断
 *       （1500 字符/值，只截值不删键）。调用方该怎么给就怎么给，不必自己裁。</li>
 *   <li><b>questions 用 laya 原生 schema</b>：{@code {"type": "choice"|"score"|"noul",
 *       "instructions": "...", "criteria": {...} 或 [...]}}，见
 *       {@code tools/laya-sidecar/README.md}。题量宜小（几道），别拿它当批量打分通道。</li>
 *   <li><b>耗时是百毫秒级</b>（常驻边车单次 33ms 上下 + 网络），但仍可能重试一次；
 *       别在事务里同步调用——消费方按 FR-04 走 {@code @Async}。</li>
 * </ul>
 */
public interface DecisionEngine {

    /**
     * 一次决策调用。<b>不抛异常</b>。
     *
     * @param state     上下文快照（键值对，值可以是嵌套 map/list；实现方负责截断）
     * @param questions 题 id → laya 原生题目 schema；题 id 是调用方与答案之间的唯一键
     * @return 模型答案 + 路由信息；失败时 {@link DecisionResult#degraded()} 为真
     */
    DecisionResult decide(Map<String, Object> state, Map<String, Map<String, Object>> questions);

    /**
     * 配置侧可用性：空 = 现在调用会真的打到边车；有值 = 降级原因（未配置平台默认 DECISION
     * 端点 / 端点不完整），供 UI 提前把入口置灰并说明原因（FR-07），不必等一次失败往返。
     *
     * <p><b>只管配置，不探活</b>：返回空不代表边车此刻活着（那要发一次真实调用才知道）。
     * 返回值已脱敏，可直接展示。</p>
     */
    Optional<String> unavailableReason();
}
