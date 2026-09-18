package com.devmind.integration.controller;

import com.devmind.integration.dto.JiraAssignableUserView;
import com.devmind.integration.dto.JiraCreateFieldsView;
import com.devmind.integration.dto.JiraPushDefaultsRequest;
import com.devmind.integration.dto.JiraPushDefaultsView;
import com.devmind.integration.dto.JiraPushOptionsView;
import com.devmind.integration.service.JiraPushService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CAP-47 FR-10：项目级 Jira 推送默认值（一个项目一行，打开推送弹窗时自动带入）。
 *
 * <p>GET 在未配置时返回 200 + 空 body（而非 404）——「还没配」是正常态，不是错误，
 * 前端据此渲染空表单。
 *
 * <p>同组下还挂了三个**项目作用域**的只读选项端点（options / assignable-users / create-fields）：
 * 配置页不挂在任何需求上，而需求侧的同类端点在路径里带 rid。选项本身与需求无关，
 * 故这里只校验项目存在。
 */
@RestController
@RequestMapping("/api/projects/{pid}/jira-push-defaults")
public class ProjectJiraPushDefaultsController {

    private final JiraPushService pushService;

    public ProjectJiraPushDefaultsController(JiraPushService pushService) {
        this.pushService = pushService;
    }

    @GetMapping
    public JiraPushDefaultsView get(@PathVariable String pid) {
        return pushService.getPushDefaults(pid);
    }

    /** 整行覆盖式保存（upsert）：请求里没给的字段即被清空 */
    @PutMapping
    public JiraPushDefaultsView save(@PathVariable String pid, @RequestBody JiraPushDefaultsRequest req) {
        return pushService.savePushDefaults(pid, req);
    }

    /** 选实例/项目后拉 Jira 项目 / 任务类型 / 优先级（配置页的 Select 数据源） */
    @GetMapping("/options")
    public JiraPushOptionsView options(@PathVariable String pid,
                                       @RequestParam("integrationId") Long integrationId,
                                       @RequestParam(value = "jiraProjectKey", required = false)
                                       String jiraProjectKey) {
        return pushService.optionsForProject(pid, integrationId, jiraProjectKey);
    }

    /** 默认经办人候选（q 为空取默认列表）；失败由前端降级为纯文本输入 */
    @GetMapping("/assignable-users")
    public List<JiraAssignableUserView> assignableUsers(@PathVariable String pid,
                                                        @RequestParam("integrationId") Long integrationId,
                                                        @RequestParam(value = "jiraProjectKey", required = false)
                                                        String jiraProjectKey,
                                                        @RequestParam(value = "q", required = false) String q) {
        return pushService.assignableUsersForProject(pid, integrationId, jiraProjectKey, q);
    }

    /**
     * 选定「实例 + 项目 + 任务类型」后的必填字段清单（createmeta）——配置页据此渲染动态字段默认值输入项。
     * 不抛错：拉取失败降级为空表 + error（与推送弹窗同款口径）。
     */
    @GetMapping("/create-fields")
    public JiraCreateFieldsView createFields(@PathVariable String pid,
                                             @RequestParam("integrationId") Long integrationId,
                                             @RequestParam("jiraProjectKey") String jiraProjectKey,
                                             @RequestParam("issueTypeId") String issueTypeId) {
        return pushService.createFieldsForProject(pid, integrationId, jiraProjectKey, issueTypeId);
    }
}
