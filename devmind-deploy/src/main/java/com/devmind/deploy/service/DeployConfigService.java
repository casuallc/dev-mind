package com.devmind.deploy.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.deploy.dto.DeployConfigRequest;
import com.devmind.deploy.dto.DeployConfigView;
import com.devmind.deploy.dto.DeployStepRequest;
import com.devmind.deploy.model.DeployConfigEntity;
import com.devmind.deploy.model.DeployStep;
import com.devmind.deploy.repo.DeployConfigRepository;
import tools.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CAP-09 FR-01 部署计划配置：每项目一份（部署步骤 + 回滚步骤），步骤引用 CAP-07 模板 code。
 * 缺省返回空视图；保存时校验步骤非空。
 */
@Service
public class DeployConfigService {

    private final DeployConfigRepository repo;
    private final ObjectMapper mapper;

    public DeployConfigService(DeployConfigRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    public DeployConfigView get(String projectId) {
        DeployConfigEntity e = repo.findByProjectId(projectId);
        if (e == null) {
            return new DeployConfigView(projectId, List.of(), List.of(), null);
        }
        return new DeployConfigView(projectId, toRequests(e.getStepsJson()), toRequests(e.getRollbackStepsJson()), e.getUpdatedAt());
    }

    public DeployConfigView update(String projectId, DeployConfigRequest req) {
        if (req == null || req.steps() == null || req.steps().isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "部署步骤不能为空");
        }
        for (DeployStepRequest s : req.steps()) {
            validateStep(s);
        }
        for (DeployStepRequest s : req.rollbackSteps() == null ? List.<DeployStepRequest>of() : req.rollbackSteps()) {
            validateStep(s);
        }
        DeployConfigEntity e = repo.findByProjectId(projectId);
        if (e == null) {
            e = new DeployConfigEntity();
            e.setProjectId(projectId);
        }
        e.setStepsJson(toJson(req.steps()));
        e.setRollbackStepsJson(toJson(req.rollbackSteps() == null ? List.of() : req.rollbackSteps()));
        e.setUpdatedAt(Instant.now());
        DeployConfigEntity saved = repo.save(e);
        return new DeployConfigView(projectId, req.steps(), req.rollbackSteps(), saved.getUpdatedAt());
    }

    /** 删除配置（测试清理用；不存在则忽略） */
    public void delete(String projectId) {
        DeployConfigEntity e = repo.findByProjectId(projectId);
        if (e != null) {
            repo.delete(e);
        }
    }

    /** 供 DeploymentService 取配置 */
    public DeployConfigEntity requireConfig(String projectId) {
        DeployConfigEntity e = repo.findByProjectId(projectId);
        if (e == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "项目未配置部署计划（请在项目管理 → 部署 中配置 deploy-config）");
        }
        return e;
    }

    private void validateStep(DeployStepRequest s) {
        if (s.name() == null || s.name().isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "步骤名不能为空");
        }
        if (s.type() == null || s.type().isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "步骤类型不能为空（artifact/backup/deploy/start/health）");
        }
        if (s.templateCode() == null || s.templateCode().isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "步骤必须指定模板 code（CAP-07 白名单）");
        }
    }

    private String toJson(List<DeployStepRequest> steps) {
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (DeployStepRequest s : steps) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", s.name());
                m.put("type", s.type());
                m.put("templateCode", s.templateCode());
                m.put("params", s.params() == null ? Map.of() : s.params());
                out.add(m);
            }
            return mapper.writeValueAsString(out);
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.INTERNAL, "部署配置序列化失败");
        }
    }

    private List<DeployStepRequest> toRequests(String json) {
        List<DeployStep> steps = parse(json);
        return steps.stream()
                .map(s -> new DeployStepRequest(s.name(), s.type(), s.templateCode(), s.params()))
                .toList();
    }

    List<DeployStep> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<DeployStep> out = new ArrayList<>();
            var arr = mapper.readTree(json);
            if (arr.isArray()) {
                for (var n : arr) {
                    Map<String, String> params = new LinkedHashMap<>();
                    var p = n.path("params");
                    if (p.isObject()) {
                        for (var e : p.properties()) {
                            params.put(e.getKey(), e.getValue().isNull() ? "" : e.getValue().asText());
                        }
                    }
                    out.add(new DeployStep(text(n, "name"), text(n, "type"), text(n, "templateCode"), params));
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String text(tools.jackson.databind.JsonNode node, String field) {
        var v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
