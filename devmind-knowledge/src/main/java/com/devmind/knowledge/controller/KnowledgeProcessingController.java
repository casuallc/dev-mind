package com.devmind.knowledge.controller;
import com.devmind.knowledge.access.KnowledgeProcessingService;
import com.devmind.knowledge.model.KnowledgeProcessingApprovalEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequestMapping("/api/knowledge/processing")
public class KnowledgeProcessingController {
    private final KnowledgeProcessingService service;
    public KnowledgeProcessingController(KnowledgeProcessingService service){this.service=service;}
    @GetMapping("/{type}/{id}") public List<KnowledgeProcessingApprovalEntity> list(@PathVariable String type,@PathVariable Long id){return service.list(type,id);}
    @PutMapping("/{type}/{id}") public List<KnowledgeProcessingApprovalEntity> replace(@PathVariable String type,@PathVariable Long id,@RequestBody List<KnowledgeProcessingService.Approval> values){return service.replace(type,id,values);}
}
