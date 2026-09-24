package com.devmind.bookmark.controller;

import com.devmind.bookmark.dto.BookmarkGroupRequest;
import com.devmind.bookmark.dto.BookmarkGroupView;
import com.devmind.bookmark.service.BookmarkGroupService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** CAP-64 FR-02 分组 REST API（我的分组树，owner 隔离）。 */
@RestController
@RequestMapping("/api/bookmark-groups")
public class BookmarkGroupController {

    private final BookmarkGroupService service;

    public BookmarkGroupController(BookmarkGroupService service) {
        this.service = service;
    }

    @GetMapping
    public List<BookmarkGroupView> list() {
        return service.tree();
    }

    @PostMapping
    public BookmarkGroupView create(@Valid @RequestBody BookmarkGroupRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public BookmarkGroupView update(@PathVariable Long id, @Valid @RequestBody BookmarkGroupRequest req) {
        return service.update(id, req);
    }

    /** cascade 默认 false：组内收藏落未分组；cascade=true 级联删除整棵子树。 */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean cascade) {
        service.delete(id, cascade);
    }
}
