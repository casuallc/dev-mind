package com.devmind.classify.playground;

import com.devmind.classify.playground.dto.PlaygroundRunRequest;
import com.devmind.classify.playground.dto.PlaygroundRunView;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** CAP-57 FR-04 在线试分类端点。 */
@RestController
@RequestMapping("/api/classify/playground")
public class ClassifyPlaygroundController {

    private final ClassifyPlaygroundService service;

    public ClassifyPlaygroundController(ClassifyPlaygroundService service) {
        this.service = service;
    }

    @PostMapping("/run")
    public PlaygroundRunView run(@RequestBody PlaygroundRunRequest req) {
        return service.run(req);
    }

    @GetMapping("/sample")
    public Map<String, Object> sample() {
        return service.sample();
    }
}
