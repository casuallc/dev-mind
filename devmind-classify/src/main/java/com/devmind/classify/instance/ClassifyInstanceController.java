package com.devmind.classify.instance;

import com.devmind.classify.instance.dto.ClassifyInstanceRequest;
import com.devmind.classify.instance.dto.ClassifyInstanceView;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CAP-57 FR-02 实例管控端点：CRUD + 起停/重启/实时状态。health 视图取最近一次轮询快照
 * （槽位/设备/sources；轮询间隔见 {@code devmind.classify.health-interval-ms}，默认 30s），
 * 要即时体感用 {@code /status}（实时向节点发 status 帧）。
 */
@RestController
@RequestMapping("/api/classify/instances")
public class ClassifyInstanceController {

    private final ClassifyInstanceService service;

    public ClassifyInstanceController(ClassifyInstanceService service) {
        this.service = service;
    }

    @GetMapping
    public List<ClassifyInstanceView> list() {
        return service.list();
    }

    @PostMapping
    public ClassifyInstanceView create(@RequestBody ClassifyInstanceRequest req) {
        return service.create(req);
    }

    @GetMapping("/{id}")
    public ClassifyInstanceView get(@PathVariable long id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    public ClassifyInstanceView update(@PathVariable long id, @RequestBody ClassifyInstanceRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        service.delete(id);
    }

    @PostMapping("/{id}/start")
    public ClassifyInstanceView start(@PathVariable long id) {
        return service.start(id);
    }

    @PostMapping("/{id}/stop")
    public ClassifyInstanceView stop(@PathVariable long id) {
        return service.stop(id);
    }

    @PostMapping("/{id}/restart")
    public ClassifyInstanceView restart(@PathVariable long id) {
        return service.restart(id);
    }

    /** 实时状态（向节点发 status 帧；进程已退出会回写 STOPPED） */
    @GetMapping("/{id}/status")
    public ClassifyInstanceView liveStatus(@PathVariable long id) {
        return service.liveStatus(id);
    }
}
