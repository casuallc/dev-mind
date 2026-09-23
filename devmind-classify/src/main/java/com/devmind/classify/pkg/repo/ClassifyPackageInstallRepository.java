package com.devmind.classify.pkg.repo;

import com.devmind.classify.pkg.model.ClassifyPackageInstallEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClassifyPackageInstallRepository extends JpaRepository<ClassifyPackageInstallEntity, Long> {

    Optional<ClassifyPackageInstallEntity> findByPackageIdAndNodeId(Long packageId, String nodeId);

    List<ClassifyPackageInstallEntity> findByPackageIdOrderByIdDesc(Long packageId);

    List<ClassifyPackageInstallEntity> findByNodeIdOrderByIdDesc(String nodeId);
}
