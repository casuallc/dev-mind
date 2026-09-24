package com.devmind.bookmark.controller;

import com.devmind.bookmark.dto.BookmarkTagRequest;
import com.devmind.bookmark.dto.BookmarkTagSummary;
import com.devmind.bookmark.service.BookmarkTagService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** CAP-64 FR-03 标签 REST API（(owner_id, name) 唯一）。 */
@RestController
@RequestMapping("/api/bookmark-tags")
public class BookmarkTagController {

    private final BookmarkTagService service;

    public BookmarkTagController(BookmarkTagService service) {
        this.service = service;
    }

    @GetMapping
    public List<BookmarkTagSummary> list() {
        return service.list();
    }

    /** 同名已存在时幂等返回既有标签（前端边填边建标签的路径依赖此口径）。 */
    @PostMapping
    public BookmarkTagSummary create(@Valid @RequestBody BookmarkTagRequest req) {
        return service.create(req.name());
    }

    @PutMapping("/{id}")
    public BookmarkTagSummary rename(@PathVariable Long id, @Valid @RequestBody BookmarkTagRequest req) {
        return service.rename(id, req.name());
    }

    /** 删除标签仅解除关联，不动收藏。 */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
