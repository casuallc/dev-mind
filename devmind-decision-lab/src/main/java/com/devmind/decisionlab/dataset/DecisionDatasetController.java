package com.devmind.decisionlab.dataset;

import com.devmind.common.dto.PageView;
import com.devmind.decisionlab.dataset.dto.DatasetDetail;
import com.devmind.decisionlab.dataset.dto.DatasetItemDetail;
import com.devmind.decisionlab.dataset.dto.DatasetItemRequest;
import com.devmind.decisionlab.dataset.dto.DatasetItemView;
import com.devmind.decisionlab.dataset.dto.DatasetRequest;
import com.devmind.decisionlab.dataset.dto.DatasetView;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CAP-56 FR-02 评测集 API（{@code /api/decision/datasets}）。
 *
 * <p>分页口径与全平台一致：{@code page} 从 0 起、{@code size} 限制 [1,200]。</p>
 *
 * <p><b>写操作全部集中在草稿</b>：冻结后条目只读，服务层拒绝并在提示里说清该走「修订为新版本」
 * ——把这条规则放在服务层而不是靠控制器的路径名暗示，是因为将来多一条写路由就会多一次忘记它的机会。</p>
 */
@RestController
@RequestMapping("/api/decision/datasets")
public class DecisionDatasetController {

    private final DatasetService service;

    public DecisionDatasetController(DatasetService service) {
        this.service = service;
    }

    @GetMapping
    public PageView<DatasetView> list(@RequestParam(required = false) String kind,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return service.list(kind, page, size);
    }

    @PostMapping
    public DatasetDetail create(@RequestBody DatasetRequest req) {
        return service.create(req);
    }

    /**
     * 对照组模板（前端「新建样本」的预填来源）。
     *
     * <p>路径是字面量，与下面的 {@code /{id}} 同时存在也不会打架：Spring 的路径匹配里
     * 字面量段优先级高于变量段，{@code /templates} 永远走这里。</p>
     */
    @GetMapping("/templates")
    public List<CaseGroupTemplates.Template> templates() {
        return service.templates();
    }

    @GetMapping("/{id}")
    public DatasetDetail get(@PathVariable Long id) {
        return service.detail(id);
    }

    @PutMapping("/{id}")
    public DatasetDetail update(@PathVariable Long id, @RequestBody DatasetRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    /** 冻结 = 这份集定格成指标的分母（校验见 {@code DatasetService.freeze}） */
    @PostMapping("/{id}/freeze")
    public DatasetDetail freeze(@PathVariable Long id) {
        return service.freeze(id);
    }

    /** 修订为新版本：冻结集要改内容的唯一入口 */
    @PostMapping("/{id}/revise")
    public DatasetDetail revise(@PathVariable Long id) {
        return service.revise(id);
    }

    // ---------------- 样本 ----------------

    @GetMapping("/{id}/items")
    public PageView<DatasetItemView> items(@PathVariable Long id,
                                          @RequestParam(required = false) String caseGroup,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return service.items(id, caseGroup, page, size);
    }

    @PostMapping("/{id}/items")
    public DatasetItemDetail addItem(@PathVariable Long id, @RequestBody DatasetItemRequest req) {
        return service.addItem(id, req);
    }

    @GetMapping("/{id}/items/{itemId}")
    public DatasetItemDetail item(@PathVariable Long id, @PathVariable Long itemId) {
        return service.item(id, itemId);
    }

    @PutMapping("/{id}/items/{itemId}")
    public DatasetItemDetail replaceItem(@PathVariable Long id, @PathVariable Long itemId,
                                        @RequestBody DatasetItemRequest req) {
        return service.replaceItem(id, itemId, req);
    }

    @DeleteMapping("/{id}/items/{itemId}")
    public void deleteItem(@PathVariable Long id, @PathVariable Long itemId) {
        service.deleteItem(id, itemId);
    }
}
