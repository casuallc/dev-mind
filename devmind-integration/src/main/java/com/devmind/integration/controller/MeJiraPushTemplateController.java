package com.devmind.integration.controller;

import com.devmind.integration.dto.JiraAssignableUserView;
import com.devmind.integration.dto.JiraCreateFieldsView;
import com.devmind.integration.dto.JiraPushOptionsView;
import com.devmind.integration.dto.JiraPushTemplateRequest;
import com.devmind.integration.dto.JiraPushTemplateView;
import com.devmind.integration.service.JiraPushService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * CAP-47 FR-10：个人 Jira 推送模板（唯一键 = 用户 + 实例 + Jira 项目 + 任务类型）。
 * 推送弹窗选定组合后自动带入匹配模板的 优先级/经办人/标签/动态字段默认值。
 *
 * <p>任何登录用户可用（SecurityConfig 默认 authenticated），服务层以认证上下文 userId 隔离，
 * ADMIN 也不能读他人记录（同 CAP-35 我的第三方账号口径）。
 *
 * <p>同组下还挂了三个**个人作用域**的只读选项端点（options / assignable-users / create-fields）：
 * 配置页不挂在任何需求/项目上，选项本身与二者无关，只验实例可用。降级口径与推送弹窗一致。
 */
@RestController
@RequestMapping("/api/me/jira-push-templates")
public class MeJiraPushTemplateController {

    private final JiraPushService pushService;

    public MeJiraPushTemplateController(JiraPushService pushService) {
        this.pushService = pushService;
    }

    @GetMapping
    public List<JiraPushTemplateView> list() {
        return pushService.listMyTemplates();
    }

    /** 整行覆盖式保存（upsert，按 实例+项目+类型 判重）：请求里没给的字段即被清空 */
    @PutMapping
    public JiraPushTemplateView save(@RequestBody JiraPushTemplateRequest req) {
        return pushService.saveMyTemplate(req);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        pushService.deleteMyTemplate(id);
        return Map.of("ok", true);
    }

    /** 选实例/项目后拉 Jira 项目 / 任务类型 / 优先级（配置表单的 Select 数据源） */
    @GetMapping("/options")
    public JiraPushOptionsView options(@RequestParam("integrationId") Long integrationId,
                                       @RequestParam(value = "jiraProjectKey", required = false)
                                       String jiraProjectKey) {
        return pushService.optionsMine(integrationId, jiraProjectKey);
    }

    /** 默认经办人候选（q 为空取默认列表）；失败由前端降级为纯文本输入 */
    @GetMapping("/assignable-users")
    public List<JiraAssignableUserView> assignableUsers(@RequestParam("integrationId") Long integrationId,
                                                        @RequestParam(value = "jiraProjectKey", required = false)
                                                        String jiraProjectKey,
                                                        @RequestParam(value = "q", required = false) String q) {
        return pushService.assignableUsersMine(integrationId, jiraProjectKey, q);
    }

    /**
     * 选定「实例 + 项目 + 任务类型」后的必填字段清单（createmeta）——配置表单据此渲染动态字段默认值输入项。
     * 不抛错：拉取失败降级为空表 + error（与推送弹窗同款口径）。
     */
    @GetMapping("/create-fields")
    public JiraCreateFieldsView createFields(@RequestParam("integrationId") Long integrationId,
                                             @RequestParam("jiraProjectKey") String jiraProjectKey,
                                             @RequestParam("issueTypeId") String issueTypeId) {
        return pushService.createFieldsMine(integrationId, jiraProjectKey, issueTypeId);
    }
}
