package com.devmind.attachment.controller;

import com.devmind.attachment.dto.AttachmentView;
import com.devmind.attachment.dto.ScopeUpdateRequest;
import com.devmind.attachment.service.AttachmentService;
import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * CAP-32 公共附件 REST API。/raw：图片 inline 直链（图床），非图片 attachment 下载；
 * 鉴权支持 Authorization header 与 GET ?access_token=（JwtAuthFilter 回退）。
 */
@RestController
@RequestMapping("/api/attachments")
public class AttachmentController {

    private final AttachmentService service;

    public AttachmentController(AttachmentService service) {
        this.service = service;
    }

    @PostMapping
    public AttachmentView upload(@RequestParam("file") MultipartFile file,
                                 @RequestParam(required = false) String scope,
                                 @RequestParam(required = false) String description) {
        return service.upload(file, scope, description);
    }

    @GetMapping
    public List<AttachmentView> list(@RequestParam(required = false) String scope,
                                     @RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) String type) {
        return service.list(scope, keyword, type);
    }

    @GetMapping("/{attachmentId}")
    public AttachmentView get(@PathVariable String attachmentId) {
        return service.get(attachmentId);
    }

    @GetMapping("/{attachmentId}/raw")
    public ResponseEntity<Resource> raw(@PathVariable String attachmentId) {
        AttachmentService.RawAttachment raw = service.raw(attachmentId);
        Resource resource = new FileSystemResource(raw.path());
        ContentDisposition disposition = raw.entity().isImage()
                ? ContentDisposition.inline().build()
                : ContentDisposition.attachment()
                        .filename(raw.entity().getOriginalName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(raw.entity().getContentType()))
                .contentLength(raw.entity().getSizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate())
                .body(resource);
    }

    @PutMapping("/{attachmentId}/scope")
    public AttachmentView updateScope(@PathVariable String attachmentId,
                                      @Valid @RequestBody ScopeUpdateRequest req) {
        return service.updateScope(attachmentId, req.scope());
    }

    @DeleteMapping("/{attachmentId}")
    public void delete(@PathVariable String attachmentId) {
        service.delete(attachmentId);
    }
}
