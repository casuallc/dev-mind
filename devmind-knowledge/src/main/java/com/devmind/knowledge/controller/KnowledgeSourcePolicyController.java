package com.devmind.knowledge.controller;
import com.devmind.knowledge.access.KnowledgeSourcePolicyService;
import com.devmind.knowledge.model.KnowledgeSourceBindingEntity;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/knowledge/entries/{id}/source-policy")
public class KnowledgeSourcePolicyController {
    private final KnowledgeSourcePolicyService service;
    public KnowledgeSourcePolicyController(KnowledgeSourcePolicyService service){this.service=service;}
    @GetMapping public KnowledgeSourceBindingEntity get(@PathVariable Long id){return service.get(id);}
    @PutMapping public KnowledgeSourceBindingEntity configure(@PathVariable Long id,@RequestBody KnowledgeSourcePolicyService.Configure r){return service.configure(id,r);}
}
