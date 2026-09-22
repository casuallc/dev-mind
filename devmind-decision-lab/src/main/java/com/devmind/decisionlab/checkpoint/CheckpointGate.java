package com.devmind.decisionlab.checkpoint;

import com.devmind.common.decision.DecisionGate;
import com.devmind.decisionlab.checkpoint.model.DecisionCheckpointEntity;
import com.devmind.decisionlab.checkpoint.repo.DecisionCheckpointRepository;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * CAP-56 FR-07 准入闸门的唯一实现：库里<b>一份通过验证的产物都没有</b>时，决策能力一律不可用。
 *
 * <p>这是 CAP-56 全部工作的收口处——评测、基线、校准、微调全都只是"让人有依据按下验证"的手段，
 * 真正拦住"用没人看过的模型做判断"的，就是这二十来行。所以要克制：
 * <ul>
 *   <li><b>只看 {@code verified}，不看指标好不好</b>：多少 ECE、比基线高几个点，是人在<br>
 *       {@code verified_note} 里写的判断依据；让闸门自己判指标好坏，等于把"这个模型适不适合我的场景"
 *       这种只有人能回答的问题编成阈值，而阈值一旦写死就会被绕过（调数据集去凑）。</li>
 *   <li><b>不查边车</b>：serve 自检是给人看的诊断，不是放行条件。把它接进闸门的话，一次网络抖动
 *       就会关掉整个决策能力，而"边车没加载这个槽位"这种真正的问题自检已经明确说了。</li>
 *   <li><b>缓存</b>：无。撤销放行必须立即生效（{@code HttpDecisionEngine} 每次询问都重新查库），
 *       缓存意味着人在实验室里点了撤销、按钮还亮着。</li>
 * </ul>
 *
 * <p>判定口径是"平台里有任意一份已验证"而不是"被服务的那个槽位已验证"：边车按语言/脚本自己路由
 * checkpoint，服务端在调用前并不知道这次会命中哪个槽位。故闸门管的是"有没有人验过模型"这道总闸，
 * 槽位级的一致性交给登记页的 serve 自检（人看得见、按槽位核对）。</p>
 *
 * <p>消息里<b>必须给出下一步</b>：这一条会被知识库分诊按钮的置灰提示原样显示，而"模型不可用"
 * 这种话除了让人来问我们，什么也没解决。</p>
 */
@Component
public class CheckpointGate implements DecisionGate {

    private final DecisionCheckpointRepository repo;

    public CheckpointGate(DecisionCheckpointRepository repo) {
        this.repo = repo;
    }

    @Override
    public Optional<String> unavailableReason() {
        // 放行路径上最常走的是这一支：一次轻查询（verified 行极少）
        if (!repo.findByVerifiedTrueOrderByIdAsc().isEmpty()) {
            return Optional.empty();
        }
        // 被拦住时才多查一次，只为把消息说具体（登记过几份、最近那份叫什么）
        Page<DecisionCheckpointEntity> newest = repo.findAllByOrderByIdDesc(PageRequest.of(0, 1));
        long total = newest.getTotalElements();
        if (total == 0) {
            return Optional.of("决策模型未通过准入：决策实验室里还没有登记任何模型产物 —— "
                    + "到「后台 → 决策实验室 → 产物登记」登记一份，跑通 serve 自检、看过指标后点「验证通过」");
        }
        String latest = newest.getContent().isEmpty() ? "" : "「" + newest.getContent().get(0).getName() + "」";
        return Optional.of("决策模型未通过准入：已登记 " + total + " 份模型产物（最近 " + latest
                + "）但都没有通过验证 —— 到「后台 → 决策实验室」看过它的评测指标后点「验证通过」");
    }
}
