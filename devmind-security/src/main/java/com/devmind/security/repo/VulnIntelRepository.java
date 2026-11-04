package com.devmind.security.repo;

import com.devmind.security.model.VulnIntelEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface VulnIntelRepository extends JpaRepository<VulnIntelEntity, String> {

    List<VulnIntelEntity> findByCveIn(Collection<String> cves);
}
