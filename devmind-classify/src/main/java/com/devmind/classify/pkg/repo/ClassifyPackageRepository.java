package com.devmind.classify.pkg.repo;

import com.devmind.classify.pkg.model.ClassifyPackageEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClassifyPackageRepository extends JpaRepository<ClassifyPackageEntity, Long> {

    Optional<ClassifyPackageEntity> findByKindAndNameAndPkgVersion(String kind, String name, String pkgVersion);
}
