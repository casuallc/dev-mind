package com.devmind.serveradapter.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.serveradapter.dto.TemplateRequest;
import com.devmind.serveradapter.dto.TemplateView;
import com.devmind.serveradapter.model.ScriptTemplateEntity;
import com.devmind.serveradapter.repo.ScriptTemplateRepository;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 命令模板白名单 CRUD（CAP-07 FR-05）：code 项目内唯一；params schema 序列化存 JSON。
 */
@Service
public class ScriptTemplateService {

    private final ScriptTemplateRepository repo;
    private final ObjectMapper mapper;

    public ScriptTemplateService(ScriptTemplateRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    public List<TemplateView> list(String projectId) {
        List<ScriptTemplateEntity> list = projectId == null || projectId.isBlank()
                ? repo.findAll().stream().sorted((a, b) -> a.getProjectId().compareTo(b.getProjectId())).toList()
                : repo.findByProjectIdOrderByCodeAsc(projectId);
        return list.stream().map(this::toView).toList();
    }

    public TemplateView create(TemplateRequest req) {
        if (req.projectId() == null || req.projectId().isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "projectId 不能为空");
        }
        if (repo.existsByProjectIdAndCode(req.projectId(), req.code().trim())) {
            throw new DevMindException(ErrorCode.CONFLICT, "模板 code 已存在: " + req.code());
        }
        ScriptTemplateEntity e = new ScriptTemplateEntity();
        e.setProjectId(req.projectId().trim());
        e.setCode(req.code().trim());
        e.setName(req.name().trim());
        e.setTemplateText(req.templateText());
        e.setParamsSchema(toParamsJson(req.params()));
        e.setAllowed(join(req.allowed()));
        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return toView(repo.save(e));
    }

    public TemplateView update(Long id, TemplateRequest req) {
        ScriptTemplateEntity e = repo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "模板不存在: " + id));
        // 改 code 时检查同项目冲突
        if (req.code() != null && !req.code().isBlank()
                && !req.code().trim().equals(e.getCode())
                && repo.existsByProjectIdAndCode(e.getProjectId(), req.code().trim())) {
            throw new DevMindException(ErrorCode.CONFLICT, "模板 code 已存在: " + req.code());
        }
        if (req.code() != null && !req.code().isBlank()) {
            e.setCode(req.code().trim());
        }
        if (req.name() != null && !req.name().isBlank()) {
            e.setName(req.name().trim());
        }
        if (req.templateText() != null) {
            e.setTemplateText(req.templateText());
        }
        if (req.params() != null) {
            e.setParamsSchema(toParamsJson(req.params()));
        }
        if (req.allowed() != null) {
            e.setAllowed(join(req.allowed()));
        }
        e.setUpdatedAt(Instant.now());
        return toView(repo.save(e));
    }

    public void delete(Long id) {
        if (!repo.existsById(id)) {
            throw new DevMindException(ErrorCode.NOT_FOUND, "模板不存在: " + id);
        }
        repo.deleteById(id);
    }

    // ---------- 内部 ----------

    private String toParamsJson(List<TemplateRequest.Param> params) {
        if (params == null || params.isEmpty()) {
            return "[]";
        }
        ArrayNode arr = mapper.createArrayNode();
        for (TemplateRequest.Param p : params) {
            ObjectNode o = mapper.createObjectNode();
            o.put("name", p.name());
            o.put("required", Boolean.TRUE.equals(p.required()));
            if (p.label() != null) {
                o.put("label", p.label());
            }
            if (p.defaultValue() != null) {
                o.put("defaultValue", p.defaultValue());
            }
            arr.add(o);
        }
        return arr.toString();
    }

    private TemplateView toView(ScriptTemplateEntity e) {
        List<TemplateView.Param> params = new ArrayList<>();
        String schema = e.getParamsSchema();
        if (schema != null && !schema.isBlank()) {
            try {
                var node = mapper.readTree(schema);
                if (node.isArray()) {
                    for (var n : node) {
                        params.add(new TemplateView.Param(
                                n.path("name").asText(null),
                                n.path("required").asBoolean(false),
                                n.path("label").asText(null),
                                n.path("defaultValue").asText(null)));
                    }
                }
            } catch (Exception ex) {
                // 损坏的 schema 视为无参数
            }
        }
        return new TemplateView(e.getId(), e.getProjectId(), e.getCode(), e.getName(), e.getTemplateText(),
                params, splitAllowed(e.getAllowed()), e.getCreatedAt(), e.getUpdatedAt());
    }

    private List<String> splitAllowed(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String s : csv.split(",")) {
            if (!s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    private String join(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        return String.join(",", list.stream().map(String::trim).toList());
    }
}
