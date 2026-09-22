package com.devmind.decision.record.controller;

import com.devmind.common.dto.PageView;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.decision.record.dto.DecisionRecordDetail;
import com.devmind.decision.record.dto.DecisionRecordView;
import com.devmind.decision.record.dto.DecisionRecordViews;
import com.devmind.decision.record.export.LayaTrainingJsonl;
import com.devmind.decision.record.model.DecisionRecordEntity;
import com.devmind.decision.record.repo.DecisionRecordRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CAP-55 FR-05 决策记录查询与训练集导出（FR-07 记录页的数据源）。
 *
 * <p>分页口径与全平台一致：{@code page} 从 0 起、{@code size} 限制 [1,200]；
 * {@code since} 按<b>日期</b>收（{@code yyyy-MM-dd}，本机时区当天 00:00 起）——与工时/审计的
 * 时间筛选同一口径，前端日期选择器不必做时区换算。</p>
 *
 * <p><b>导出是下载而不是 JSON</b>：训练集是文件资产（给微调脚本喂的），带
 * {@code Content-Disposition: attachment} 让浏览器/脚本都当文件拿；
 * 内容类型 {@code application/x-ndjson} 就是 JSONL 的标准名。</p>
 */
@RestController
@RequestMapping("/api/decision/records")
public class DecisionRecordController {

    private static final Logger log = LoggerFactory.getLogger(DecisionRecordController.class);

    private static final MediaType NDJSON = new MediaType("application", "x-ndjson");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final DecisionRecordRepository repo;

    public DecisionRecordController(DecisionRecordRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public PageView<DecisionRecordView> list(@RequestParam(required = false) String capability,
                                             @RequestParam(required = false)
                                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate since,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        if (size < 1 || size > 200) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "size 取值范围 1-200");
        }
        Page<DecisionRecordEntity> result = repo.search(blankToNull(capability), sinceStart(since),
                PageRequest.of(Math.max(page, 0), size));
        return new PageView<>(result.getContent().stream().map(DecisionRecordViews::of).toList(),
                result.getTotalElements(), Math.max(page, 0), size);
    }

    /** 详情：多出 state/questions 两坨快照（抽屉里逐字回放当初的输入与题面）。 */
    @GetMapping("/{id}")
    public DecisionRecordDetail get(@PathVariable Long id) {
        DecisionRecordEntity row = repo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "决策记录不存在: " + id));
        return DecisionRecordViews.detail(row);
    }

    /**
     * 导出 laya 训练 JSONL：{@code {"state","questions","gold"}} 每行一条，含人工裁决的 gold 分布。
     * 不可训练的行（没 gold / 快照不全）自动跳过，跳过数记日志——导出文件比预期短时，
     * 第一件要能回答的就是"跳了多少"。
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String capability,
                                         @RequestParam(required = false)
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate since) {
        String cap = blankToNull(capability);
        List<DecisionRecordEntity> rows = repo.findForExport(cap, sinceStart(since));
        LayaTrainingJsonl.Export export = LayaTrainingJsonl.render(rows);
        log.info("决策训练集导出: capability={} since={} 候选={} 产出={} 跳过={}",
                cap == null ? "（全部）" : cap, since, rows.size(), export.emitted(), export.skipped());
        byte[] body = export.jsonl().getBytes(StandardCharsets.UTF_8);
        String name = "decision-records-" + (cap == null ? "all" : cap) + "-"
                + STAMP.format(LocalDateTime.now()) + ".jsonl";
        return ResponseEntity.ok()
                .contentType(NDJSON)
                .contentLength(body.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .body(body);
    }

    /** since 是日期（含当天）：转成"本机时区当天 00:00"，与 UI 上看到的日期一致 */
    private static Instant sinceStart(LocalDate since) {
        return since == null ? null : since.atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
