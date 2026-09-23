package com.devmind.classify.instance.repo;

import com.devmind.classify.instance.model.ClassifyInstanceEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClassifyInstanceRepository extends JpaRepository<ClassifyInstanceEntity, Long> {

    Optional<ClassifyInstanceEntity> findByName(String name);

    /** 删包前查引用（应用包绑定） */
    List<ClassifyInstanceEntity> findByAppPackageId(Long appPackageId);

    /** 健康轮询目标（STARTING/RUNNING/UNHEALTHY） */
    List<ClassifyInstanceEntity> findByStatusIn(Collection<String> statuses);
}
