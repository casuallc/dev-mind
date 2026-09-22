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
     * <p>名字不用 {@code findBy…} 重载：同一个名字两种返回（{@code List} / {@code Page}）虽然
     * Spring Data 分得清，读代码的人却要停下来想"这里调的是哪个"。</p>
     */
    Page<DecisionCheckpointEntity> pageByServeSlotOrderByIdDesc(String serveSlot, Pageable pageable);

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
