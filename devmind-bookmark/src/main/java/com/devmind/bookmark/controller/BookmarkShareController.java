package com.devmind.bookmark.controller;

import com.devmind.bookmark.dto.BookmarkShareRequest;
import com.devmind.bookmark.dto.BookmarkShareView;
import com.devmind.bookmark.service.BookmarkShareService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CAP-64 FR-07 分享（我发出的）。接收方视图走 {@code /api/bookmarks/shared-with-me}，
 * 不在此命名空间暴露。
 */
@RestController
@RequestMapping("/api/bookmark-shares")
public class BookmarkShareController {

    private final BookmarkShareService service;

    public BookmarkShareController(BookmarkShareService service) {
        this.service = service;
    }

    @GetMapping
    public List<BookmarkShareView> listMine() {
        return service.listMine();
    }

    @PostMapping
    public BookmarkShareView create(@Valid @RequestBody BookmarkShareRequest req) {
        return service.create(req);
    }

    /** 撤销分享：立即对接收方不可见。 */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
