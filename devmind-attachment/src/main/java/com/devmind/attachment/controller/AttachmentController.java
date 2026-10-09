package com.devmind.attachment.controller;

import com.devmind.attachment.dto.AttachmentView;
import com.devmind.attachment.dto.BatchDeleteRequest;
import com.devmind.attachment.dto.MetaUpdateRequest;
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
                                 @RequestParam(required = false) String description,
                                 @RequestParam(required = false) String tags,
                                 @RequestParam(required = false) Integer expireDays) {
        return service.upload(file, scope, description, tags, expireDays);
    }

    @GetMapping
    public List<AttachmentView> list(@RequestParam(required = false) String scope,
                                     @RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) String type,
                                     @RequestParam(required = false) String tag) {
        return service.list(scope, keyword, type, tag);
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

    /** CAP-68：改 description/tags/expiresAt（null=不变，空白串=清除；expiresAt 格式 yyyy-MM-dd HH:mm:ss）。 */
    @PutMapping("/{attachmentId}/meta")
    public AttachmentView updateMeta(@PathVariable String attachmentId,
                                     @RequestBody MetaUpdateRequest req) {
        return service.updateMeta(attachmentId, req.description(), req.tags(), req.expiresAt());
    }

    /** CAP-68：批量删除（逐项 owner/ADMIN 校验，部分失败不整单回滚）。 */
    @PostMapping("/batch-delete")
    public List<AttachmentService.BatchDeleteItemResult> batchDelete(@RequestBody BatchDeleteRequest req) {
        return service.batchDelete(req.ids());
    }

    @DeleteMapping("/{attachmentId}")
    public void delete(@PathVariable String attachmentId) {
        service.delete(attachmentId);
    }
}
