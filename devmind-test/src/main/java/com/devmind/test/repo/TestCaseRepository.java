package com.devmind.test.repo;

import com.devmind.test.model.TestCaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TestCaseRepository extends JpaRepository<TestCaseEntity, Long> {

    List<TestCaseEntity> findBySuiteIdOrderBySortAsc(Long suiteId);

    void deleteBySuiteId(Long suiteId);

    void deleteByIdIn(Collection<Long> ids);

    Optional<TestCaseEntity> findByCaseKey(String caseKey);

    boolean existsByCaseKey(String caseKey);

    List<TestCaseEntity> findByCaseKeyNull();

    List<TestCaseEntity> findBySignatureNull();

    List<TestCaseEntity> findBySignatureAndIdNot(String signature, Long id);

    /** 项目内 caseKey 唯一（excludeId 空 = 新建） */
    @Query("""
            select count(c) from TestCaseEntity c join TestSuiteEntity s on s.id = c.suiteId
            where s.projectId = :projectId and c.caseKey = :caseKey
              and (:excludeId is null or c.id <> :excludeId)
            """)
    long countCaseKeyInProject(@Param("projectId") String projectId,
                               @Param("caseKey") String caseKey,
                               @Param("excludeId") Long excludeId);

    /** 同项目同签名的疑似重复用例（只提示不合并） */
    @Query("""
            select c from TestCaseEntity c join TestSuiteEntity s on s.id = c.suiteId
            where s.projectId = :projectId and c.signature = :signature
              and (:excludeId is null or c.id <> :excludeId)
            """)
    List<TestCaseEntity> findDuplicates(@Param("projectId") String projectId,
                                        @Param("signature") String signature,
                                        @Param("excludeId") Long excludeId);

    /** 用例库检索：项目 + 套件/类型/关键字（LIKE），组合条件可空。 */
    @Query("""
            select c from TestCaseEntity c join TestSuiteEntity s on s.id = c.suiteId
            where s.projectId = :projectId
              and (:suiteId is null or c.suiteId = :suiteId)
              and (:moduleId is null or s.moduleId = :moduleId)
              and (:type is null or c.type = :type)
              and (:reviewStatus is null or c.reviewStatus = :reviewStatus)
              and (:keyword is null or lower(c.name) like lower(concat('%', :keyword, '%'))
                   or lower(c.caseKey) like lower(concat('%', :keyword, '%'))
                   or lower(c.path) like lower(concat('%', :keyword, '%')))
              and (:tags is null or lower(c.tags) like lower(concat('%', :tags, '%')))
            order by c.suiteId asc, c.sort asc
            """)
    List<TestCaseEntity> search(@Param("projectId") String projectId,
                                @Param("suiteId") Long suiteId,
                                @Param("moduleId") Long moduleId,
                                @Param("type") String type,
                                @Param("reviewStatus") String reviewStatus,
                                @Param("keyword") String keyword,
                                @Param("tags") String tags);

    long countBySuiteId(Long suiteId);

    long countBySourceAssetId(Long sourceAssetId);
}
