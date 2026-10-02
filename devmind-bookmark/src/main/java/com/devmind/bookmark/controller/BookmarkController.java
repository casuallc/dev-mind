package com.devmind.bookmark.controller;

import com.devmind.bookmark.dto.BookmarkImportRequest;
import com.devmind.bookmark.dto.BookmarkRequest;
import com.devmind.bookmark.dto.BookmarkView;
import com.devmind.bookmark.dto.CopySharedRequest;
import com.devmind.bookmark.dto.ImportResultView;
import com.devmind.bookmark.dto.MoveBookmarksRequest;
import com.devmind.bookmark.dto.MoveResultView;
import com.devmind.bookmark.dto.ProbeAcceptedView;
import com.devmind.bookmark.dto.ProbeBatchRequest;
import com.devmind.bookmark.dto.ProbeResultView;
import com.devmind.bookmark.dto.SecretView;
import com.devmind.bookmark.dto.SharedWithMeView;
import com.devmind.bookmark.service.BookmarkImportService;
import com.devmind.bookmark.service.BookmarkProbeService;
import com.devmind.bookmark.service.BookmarkService;
import com.devmind.bookmark.service.BookmarkShareService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CAP-64 收藏夹 REST API。owner 校验全在服务层（Controller 只做参数绑定与委派）。
 *
 * <p>`/shared-with-me` 与 `/move` 等字面量路径与 `/{id}` 同处一个控制器：Spring 的路径匹配
 * 字面量优先于变量，不存在歧义。</p>
 */
@RestController
@RequestMapping("/api/bookmarks")
public class BookmarkController {

    private final BookmarkService service;
    private final BookmarkProbeService probeService;
    private final BookmarkShareService shareService;
    private final BookmarkImportService importService;

    public BookmarkController(BookmarkService service,
                              BookmarkProbeService probeService,
                              BookmarkShareService shareService,
                              BookmarkImportService importService) {
        this.service = service;
        this.probeService = probeService;
        this.shareService = shareService;
        this.importService = importService;
    }

    @GetMapping
    public List<BookmarkView> list(@RequestParam(required = false) Long groupId,
                                   @RequestParam(required = false) Boolean ungrouped,
                                   @RequestParam(required = false) List<Long> tagIds,
                                   @RequestParam(required = false) String keyword,
                                   @RequestParam(required = false) String status) {
        return service.list(groupId, ungrouped, tagIds, keyword, status);
    }

    @PostMapping
    public BookmarkView create(@Valid @RequestBody BookmarkRequest req) {
        return service.create(req);
    }

    /** FR-07 我收到的分享（接收方只读命名空间，密码字段整体剔除）。 */
    @GetMapping("/shared-with-me")
    public List<SharedWithMeView> sharedWithMe() {
        return shareService.sharedWithMe();
    }

    /** FR-07 把分享来的收藏复制为我自己的。 */
    @PostMapping("/shared-with-me/copy")
    public BookmarkView copyShared(@RequestBody CopySharedRequest req) {
        return shareService.copy(req);
    }

    /** FR-02 批量转移分组（groupId=null 即未分组）。 */
    @PutMapping("/move")
    public MoveResultView move(@Valid @RequestBody MoveBookmarksRequest req) {
        return new MoveResultView(service.move(req));
    }

    /** FR-04 批量探测（异步，上限 200 条）。 */
    @PostMapping("/probe-batch")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ProbeAcceptedView probeBatch(@Valid @RequestBody ProbeBatchRequest req) {
        return new ProbeAcceptedView(probeService.probeBatch(req.ids()));
    }

    /** FR-09 导入浏览器书签（前端已解析为结构化树；单事务，同 URL 去重跳过）。 */
    @PostMapping("/import")
    public ImportResultView importBookmarks(@Valid @RequestBody BookmarkImportRequest req) {
        return importService.importTree(req);
    }

    @GetMapping("/{id}")
    public BookmarkView get(@PathVariable Long id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    public BookmarkView update(@PathVariable Long id, @Valid @RequestBody BookmarkRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    /** FR-06 记录最近访问（前端打开同时调用，失败静默）。 */
    @PostMapping("/{id}/visit")
    public void visit(@PathVariable Long id) {
        service.visit(id);
    }

    /** FR-04 单条探测（同步返回结果）。 */
    @PostMapping("/{id}/probe")
    public ProbeResultView probe(@PathVariable Long id) {
        return service.probe(id);
    }

    /** FR-05 按次取账号明文（owner only）。 */
    @GetMapping("/{id}/accounts/{aid}/secret")
    public SecretView accountSecret(@PathVariable Long id, @PathVariable("aid") String accountId) {
        return service.secret(id, accountId);
    }
}
