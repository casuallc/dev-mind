package com.devmind.decisionlab.finetune.dto;

import java.util.List;
import java.util.Map;

/**
 * CAP-56 微调详情：视图 + 报告 + 从报告里提出来的分块 + 切分明细。
 *
 * <p><b>{@code valItemIds} 单独给出</b>：它是这次实验的"考卷名单"，报告里的 val 指标只有在
 * 能指认这几条样本时才算证据。页面把它列出来，人就能去对照某条样本标得对不对——
 * 而不是只能相信一个数字。</p>
 *
 * @param report      微调脚本自己的报告（在验证切分上的指标 + 训练过程 + 校准）
 * @param perItem     验证集上的逐题明细
 * @param calibration 验证切分上拟合的温度参数（held-out 拟合，没有评测集泄漏）
 * @param train       训练过程段落（loss 曲线 / 步数 / 有效样本数）
 * @param valItemIds  验证集条目 id（此次实验实际考的那几条）
 * @param commandText 节点上实际跑的那条命令（诊断第一手材料；含节点本地路径，不含凭据）
 */
public record FinetuneDetail(FinetuneView view, Map<String, Object> report,
                             List<Map<String, Object>> perItem,
                             Map<String, Object> calibration,
                             Map<String, Object> train,
                             List<Long> valItemIds,
                             String commandText) {
}
