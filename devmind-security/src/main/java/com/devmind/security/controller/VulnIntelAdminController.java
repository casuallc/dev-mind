package com.devmind.security.controller;

import com.devmind.common.dto.PageView;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.security.model.VulnIntelEntity;
import com.devmind.security.repo.VulnIntelRepository;
import com.devmind.security.service.VulnIntelService;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 漏洞情报后台管理（CAP-66 配套）：vuln_intel 表的分页浏览 + 手工增删改 + 手动触发 EPSS/KEV 同步。
 * 全局情报数据非项目维度，挂后台「项目与资源」组；读走 GET /api/** 全认证，
 * 写/同步经 CAP-103 ApiPermissionCatalog 按 admin.vuln-intel.action.* 收紧。
 *
 * <p>注意：kev 标记与 EPSS 分数由每日同步覆写，手工改动仅当日有效。</p>
 */
@RestController
@RequestMapping("/api/security/intel")
public class VulnIntelAdminController {

    /** 排序字段白名单（防 Sort 注入到实体属性外） */
    private static final Set<String> SORTABLE = Set.of("cve", "epssScore", "epssPercentile", "updatedAt", "kev",
            "publishedAt", "lastModifiedAt", "cvssScore");
    private static final int MAX_SIZE = 200;

    private final VulnIntelRepository repo;
    private final VulnIntelService intelService;

    public VulnIntelAdminController(VulnIntelRepository repo, VulnIntelService intelService) {
        this.repo = repo;
        this.intelService = intelService;
    }

    public record VulnIntelUpsertRequest(String cve, Double epssScore, Double epssPercentile,
                                         Boolean kev, String kevDueDate) {
    }

    /** 总览统计：总量 / KEV 在榜量 / 最近更新时间 / 三路同步是否正在跑（前端轮询用） */
    public record IntelStats(long total, long kevCount, Instant lastUpdatedAt,
                             boolean epssSyncRunning, boolean kevSyncRunning, boolean nvdSyncRunning) {
    }

    @GetMapping
    public PageView<VulnIntelEntity> list(@RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) Boolean kev,
                                          @RequestParam(required = false) Double epssMin,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size,
                                          @RequestParam(defaultValue = "updatedAt") String sort,
                                          @RequestParam(defaultValue = "desc") String dir) {
        Specification<VulnIntelEntity> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (StringUtils.hasText(keyword)) {
                // description 是 varchar 非 Lob，可安全 lower()（Lob 须 cast，红线）
                String kw = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
                ps.add(cb.or(cb.like(cb.lower(root.get("cve")), kw),
                        cb.like(cb.lower(root.get("description")), kw)));
            }
            if (kev != null) {
                ps.add(kev ? cb.isTrue(root.get("kev")) : cb.isFalse(root.get("kev")));
            }
            if (epssMin != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("epssScore"), epssMin));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
        Sort s = SORTABLE.contains(sort)
                ? Sort.by("asc".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC, sort)
                : Sort.by(Sort.Direction.DESC, "updatedAt");
        Page<VulnIntelEntity> p = repo.findAll(spec,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_SIZE), s));
        return new PageView<>(p.getContent(), p.getTotalElements(), p.getNumber(), p.getSize());
    }

    @GetMapping("/stats")
    public IntelStats stats() {
        return new IntelStats(repo.count(), repo.countByKevTrue(),
                repo.findTopByOrderByUpdatedAtDesc().map(VulnIntelEntity::getUpdatedAt).orElse(null),
                intelService.isEpssSyncRunning(), intelService.isKevSyncRunning(), intelService.isNvdSyncRunning());
    }

    @PostMapping
    public VulnIntelEntity create(@RequestBody VulnIntelUpsertRequest req) {
        String cve = normalizeCve(req.cve());
        if (repo.existsById(cve)) {
            throw new DevMindException(ErrorCode.CONFLICT, "该 CVE 已存在，可改用编辑");
        }
        return repo.save(apply(new VulnIntelEntity(), cve, req));
    }

    @PutMapping("/{cve}")
    public VulnIntelEntity update(@PathVariable String cve, @RequestBody VulnIntelUpsertRequest req) {
        VulnIntelEntity e = repo.findById(cve)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "情报记录不存在: " + cve));
        return repo.save(apply(e, e.getCve(), req));
    }

    @DeleteMapping("/{cve}")
    public void delete(@PathVariable String cve) {
        if (!repo.existsById(cve)) {
            throw new DevMindException(ErrorCode.NOT_FOUND, "情报记录不存在: " + cve);
        }
        repo.deleteById(cve);
    }

    @PostMapping("/sync-epss")
    public Map<String, Object> syncEpss() {
        if (!intelService.triggerSyncEpss()) {
            throw new DevMindException(ErrorCode.CONFLICT, "EPSS 同步已在进行或情报同步已关闭");
        }
        return Map.of("started", true);
    }

    @PostMapping("/sync-kev")
    public Map<String, Object> syncKev() {
        if (!intelService.triggerSyncKev()) {
            throw new DevMindException(ErrorCode.CONFLICT, "KEV 同步已在进行或情报同步已关闭");
        }
        return Map.of("started", true);
    }

    @PostMapping("/sync-nvd")
    public Map<String, Object> syncNvd() {
        if (!intelService.triggerSyncNvd()) {
            throw new DevMindException(ErrorCode.CONFLICT, "NVD 同步已在进行或情报同步已关闭");
        }
        return Map.of("started", true);
    }

    /**
     * 按需从 NVD 补拉单条 CVE 元数据（描述/发布/修改/CVSS；展开详情自动触发）。
     * NVD 查无此 CVE → 404；NVD 不可达 → 503。
     */
    @PostMapping("/{cve}/enrich")
    public VulnIntelEntity enrich(@PathVariable String cve) {
        try {
            return intelService.enrich(cve.trim().toUpperCase(Locale.ROOT))
                    .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "NVD 查无此 CVE: " + cve));
        } catch (DevMindException de) {
            throw de; // 409 关闭/404 查无此 CVE 原样抛出，别被兜底包成 503
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new DevMindException(ErrorCode.SERVICE_UNAVAILABLE, "NVD 补拉被中断");
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.SERVICE_UNAVAILABLE, "NVD 数据源不可达: " + e.getMessage());
        }
    }

    private static VulnIntelEntity apply(VulnIntelEntity e, String cve, VulnIntelUpsertRequest req) {
        e.setCve(cve);
        if (req.epssScore() != null && (req.epssScore() < 0 || req.epssScore() > 1)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "EPSS 分数取值范围 0~1");
        }
        if (req.epssPercentile() != null && (req.epssPercentile() < 0 || req.epssPercentile() > 1)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "EPSS 百分位取值范围 0~1");
        }
        String dueDate = req.kevDueDate();
        if (dueDate != null && dueDate.length() > 16) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "KEV 修复期限过长（≤16 字符，格式 YYYY-MM-DD）");
        }
        e.setEpssScore(req.epssScore());
        e.setEpssPercentile(req.epssPercentile());
        e.setKev(Boolean.TRUE.equals(req.kev()));
        e.setKevDueDate(StringUtils.hasText(dueDate) ? dueDate.trim() : null);
        e.setUpdatedAt(Instant.now());
        return e;
    }

    private static String normalizeCve(String cve) {
        if (!StringUtils.hasText(cve)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "CVE 编号必填");
        }
        String v = cve.trim().toUpperCase(Locale.ROOT);
        if (v.length() > 32) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "CVE 编号过长（≤32 字符）");
        }
        return v;
    }
}
