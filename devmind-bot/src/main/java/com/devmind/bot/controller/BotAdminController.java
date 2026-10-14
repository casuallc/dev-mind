package com.devmind.bot.controller;

import com.devmind.bot.dto.BotBindingView;
import com.devmind.bot.model.BotBindingEntity;
import com.devmind.bot.model.BotInteractionEntity;
import com.devmind.bot.repo.BotBindingRepository;
import com.devmind.bot.repo.BotInteractionRepository;
import com.devmind.common.dto.PageView;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.ProjectService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * CAP-75 FR-14 机器人运维端点（仅 ADMIN，SecurityConfig 集中登记）：
 * 交互日志分页查询 + 群绑定查看/解绑。用户映射视图复用 integration 模块的
 * {@code /api/admin/user-platform-accounts}。
 */
@RestController
@RequestMapping("/api/bot/admin")
public class BotAdminController {

    private final BotBindingRepository bindingRepo;
    private final BotInteractionRepository interactionRepo;
    private final ProjectService projectService;

    public BotAdminController(BotBindingRepository bindingRepo,
                              BotInteractionRepository interactionRepo,
                              ProjectService projectService) {
        this.bindingRepo = bindingRepo;
        this.interactionRepo = interactionRepo;
        this.projectService = projectService;
    }

    /** 交互日志分页（channel/status/keyword 过滤，keyword 命中入站/出站/发送者）。 */
    @GetMapping("/interactions")
    public PageView<BotInteractionEntity> interactions(@RequestParam(required = false) String channel,
                                                       @RequestParam(required = false) String status,
                                                       @RequestParam(required = false) String keyword,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        Page<BotInteractionEntity> p = interactionRepo.search(blankToNull(channel),
                blankToNull(status), blankToNull(keyword),
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100)));
        return new PageView<>(p.getContent(), p.getTotalElements(), page, size);
    }

    /** 群绑定列表（联查项目名，已删项目标记）。 */
    @GetMapping("/bindings")
    public List<BotBindingView> bindings() {
        return bindingRepo.findAll().stream().map(b -> {
            String projectName = null;
            if (b.getProjectId() != null && !b.getProjectId().isBlank()) {
                try {
                    projectName = projectService.get(b.getProjectId()).name();
                } catch (Exception e) {
                    projectName = "（已删除）";
                }
            }
            return new BotBindingView(b.getId(), b.getChannel(), b.getExternalChatId(),
                    b.getChatType(), b.getOwnerSenderId(), b.getChatSessionId(),
                    b.getProjectId(), projectName, b.getActor(), b.getCreatedAt(), b.getUpdatedAt());
        }).toList();
    }

    /** 解绑群（下一条消息会懒重建绑定）。 */
    @DeleteMapping("/bindings/{id}")
    public Map<String, Object> unbind(@PathVariable Long id) {
        if (!bindingRepo.existsById(id)) {
            throw new DevMindException(ErrorCode.NOT_FOUND, "绑定记录不存在: " + id);
        }
        bindingRepo.deleteById(id);
        return Map.of("ok", true);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
