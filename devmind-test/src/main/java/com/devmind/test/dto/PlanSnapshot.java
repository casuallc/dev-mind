package com.devmind.test.dto;

import com.devmind.test.model.TestCaseEntity;

import java.util.List;

/**
 * CAP-104 M2 方案运行固化快照。方案项解析成用例快照后整个写入
 * test_plan_runs.plan_snapshot_json；子运行只取自己那份用例快照（test_runs.execution_snapshot_json），
 * 用例在快照之后被编辑/废弃也不影响历史运行。
 */
public record PlanSnapshot(
        Integer version,
        Long planId,
        String planName,
        Integer planVersion,
        String failStrategy,
        Integer concurrency,
        /** 运行前覆盖的用例默认超时（秒）；方案项/用例自身超时优先 */
        Integer timeoutSec,
        /** FR-19 选节点时的标签/能力要求（固化为快照，便于回溯） */
        List<String> labels,
        List<String> capabilities,
        Long environmentId,
        String requirementId,
        String mode,
        List<String> nodeIds,
        String agentNodeId,
        String baseUrl,
        java.util.Map<String, String> parameters,
        java.time.Instant capturedAt,
        List<PlanItemSnapshot> items) {

    public static final int CURRENT_VERSION = 1;

    public record PlanItemSnapshot(
            Long itemId,
            String refType,
            Long refId,
            Integer refVersion,
            Integer seq,
            String parallelGroup,
            String refName,
            java.util.Map<String, Object> overrides,
            List<TestCaseSnapshot> cases) {
    }

    /** 运行期用例快照：只保留执行需要的字段，避免绑定 JPA 实体生命周期。 */
    public record TestCaseSnapshot(
            Long id,
            Long suiteId,
            Integer sort,
            String name,
            String kind,
            String method,
            String path,
            String paramsJson,
            String headersJson,
            String bodyJson,
            String expectedJson,
            Boolean enabled,
            String caseKey,
            String type,
            String priority,
            String tags,
            String specJson,
            Integer caseVersion,
            String reviewStatus,
            Long sourceAssetId,
            String suiteCommit) {

        public static TestCaseSnapshot of(TestCaseEntity c, String suiteCommit) {
            return new TestCaseSnapshot(c.getId(), c.getSuiteId(), c.getSort(), c.getName(), c.getKind(),
                    c.getMethod(), c.getPath(), c.getParamsJson(), c.getHeadersJson(), c.getBodyJson(),
                    c.getExpectedJson(), c.getEnabled(), c.getCaseKey(), c.getType(), c.getPriority(),
                    c.getTags(), c.getSpecJson(), c.getCaseVersion(), c.getReviewStatus(),
                    c.getSourceAssetId(), suiteCommit);
        }

        /** 还原为不落库的实体，供既有执行器复用。 */
        public TestCaseEntity toEntity() {
            TestCaseEntity e = new TestCaseEntity();
            e.setId(id);
            e.setSuiteId(suiteId);
            e.setSort(sort);
            e.setName(name);
            e.setKind(kind);
            e.setMethod(method);
            e.setPath(path);
            e.setParamsJson(paramsJson);
            e.setHeadersJson(headersJson);
            e.setBodyJson(bodyJson);
            e.setExpectedJson(expectedJson);
            e.setEnabled(enabled == null ? true : enabled);
            e.setCaseKey(caseKey);
            e.setType(type);
            e.setPriority(priority);
            e.setTags(tags);
            e.setSpecJson(specJson);
            e.setCaseVersion(caseVersion);
            e.setReviewStatus(reviewStatus);
            e.setSourceAssetId(sourceAssetId);
            return e;
        }
    }
}
