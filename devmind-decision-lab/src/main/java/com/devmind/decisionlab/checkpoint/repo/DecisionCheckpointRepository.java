package com.devmind.decisionlab.checkpoint.repo;

import com.devmind.decisionlab.checkpoint.model.DecisionCheckpointEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DecisionCheckpointRepository extends JpaRepository<DecisionCheckpointEntity, Long> {

    Page<DecisionCheckpointEntity> findAllByOrderByIdDesc(Pageable pageable);

    Optional<DecisionCheckpointEntity> findByName(String name);

    List<DecisionCheckpointEntity> findByServeSlotOrderByIdDesc(String serveSlot);

    /**
     * 分页版（列表页按槽位筛选）。
     *
     * <p><b>名字只能叫 {@code findBy…}</b>：Spring Data 4 只认
     * {@code find/read/get/query/search/stream/count/exists/delete/remove} 这几个前缀，
     * 自造一个 {@code pageBy…} 会被当成属性路径解析，启动期直接报
     * {@code No property 'pageByServeSlot' found for type 'DecisionCheckpointEntity'}
     * （CAP-56 E2E 抓到的：整模块因此起不来，而单测不引导全上下文，看不见）。</p>
     *
     * <p>与上面的 {@code List} 版同名重载：Spring Data 按参数签名分派，带 {@code Pageable} 的
     * 那个返回 {@code Page}——同一个名字两种返回，调用处才分得清自己在翻页还是取全量。</p>
     */
    Page<DecisionCheckpointEntity> findByServeSlotOrderByIdDesc(String serveSlot, Pageable pageable);

    /** 已通过验证的那些（正常情况下 ≤ 槽位数；供闸门与"当前放行的是谁"两处用） */
    List<DecisionCheckpointEntity> findByVerifiedTrueOrderByIdAsc();

    /**
     * 某槽位上已通过验证的那一份。
     *
     * <p>查的是集合而不是单个（不加 @Query 限一条）：真出现两行时，服务层要能<b>看见</b>
     * 并修掉它，而不是被 Spring Data 的"返回多行"异常在运行期炸出来。见
     * {@code CheckpointService.verify}——它保证同一槽位只有一行 verified。</p>
     */
    List<DecisionCheckpointEntity> findByServeSlotAndVerifiedTrueOrderByIdAsc(String serveSlot);
}
