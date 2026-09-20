package com.devmind.model.controller;

import com.devmind.model.ModelEndpointService;
import com.devmind.model.dto.EndpointTestResult;
import com.devmind.model.dto.ModelEndpointApiView;
import com.devmind.model.dto.ModelEndpointRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CAP-48 模型端点管理 REST。写方法在 SecurityConfig 收紧为仅 ADMIN；
 * 凭据不明文回显（视图仅 hasApiKey），与 CAP-18 平台集成同口径。
 */
@RestController
@RequestMapping("/api/model-endpoints")
public class ModelEndpointController {

    private final ModelEndpointService service;

    public ModelEndpointController(ModelEndpointService service) {
        this.service = service;
    }

    @GetMapping
    public List<ModelEndpointApiView> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public ModelEndpointApiView get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    public ModelEndpointApiView create(@RequestBody ModelEndpointRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public ModelEndpointApiView update(@PathVariable Long id, @RequestBody ModelEndpointRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @PutMapping("/{id}/status")
    public ModelEndpointApiView changeStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return service.changeStatus(id, body.get("status"));
    }

    /** 设为平台默认（同类型唯一） */
    @PutMapping("/{id}/default")
    public ModelEndpointApiView setDefault(@PathVariable Long id) {
        return service.setDefault(id);
    }

    /** FR-03 连接测试：实调一次并回写结果 + 实测维度 */
    @PostMapping("/{id}/test")
    public EndpointTestResult test(@PathVariable Long id) {
        return service.test(id);
    }

    /** FR-03 未保存配置的连接测试（表单内预检，凭据不落库） */
    @PostMapping("/test")
    public EndpointTestResult testDraft(@RequestBody ModelEndpointRequest req) {
        return service.testDraft(req);
    }
}
