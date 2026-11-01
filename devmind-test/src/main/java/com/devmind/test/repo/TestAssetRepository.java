package com.devmind.test.repo;

import com.devmind.test.model.TestAssetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TestAssetRepository extends JpaRepository<TestAssetEntity, Long> {

    List<TestAssetEntity> findByProjectIdOrderByCreatedAtDesc(String projectId);

    List<TestAssetEntity> findByProjectIdAndReviewStatusOrderByCreatedAtDesc(String projectId, String reviewStatus);
}
