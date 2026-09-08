package com.devmind.session.service;

import com.devmind.common.agent.exec.ContextAssemblyRequest;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.ProjectService;
import com.devmind.project.model.Project;
import com.devmind.session.dto.ScenarioPreviewView;
import com.devmind.session.model.SessionScenarioEntity;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * CAP-33 FR-06 场景预览：dryRun 装配（无副作用，不 bumpHits）出「将会注入什么」。
 * 供场景管理页骨架预览与创建表单联调。
 */
@Service
public class ScenarioPreviewService {

    private final ScenarioService scenarioService;
    private final ContextAssembler assembler;
    private final ProjectService projectService;

    public ScenarioPreviewService(ScenarioService scenarioService, ContextAssembler assembler,
                                  ProjectService projectService) {
        this.scenarioService = scenarioService;
        this.assembler = assembler;
        this.projectService = projectService;
    }

    /**
     * 预览。projectId 可空：PROJECT 场景缺省取场景自身项目；显式传参与 PROJECT 场景项目不符 400
     * （与创建口径一致）。taskSpec 可空（占位符按空串渲染）。
     */
    public ScenarioPreviewView preview(String code, String projectId, String taskSpec) {
        SessionScenarioEntity s = scenarioService.requireByCode(code);
        Project project;
        if (ScenarioService.SCOPE_PROJECT.equals(s.getScope())) {
            if (projectId != null && !projectId.isBlank() && !projectId.equals(s.getProjectId())) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "PROJECT 场景只能挂到其所属项目: " + s.getProjectId());
            }
            project = projectService.requireProject(s.getProjectId());
        } else {
            project = projectId == null || projectId.isBlank() ? null : projectService.requireProject(projectId);
        }
        String rendered = scenarioService.render(s, taskSpec, project, null);
        ContextAssemblyRequest req = new ContextAssemblyRequest(
                project != null ? project.id() : null,
                project != null && project.tags() != null ? project.tags() : List.of(),
                scenarioService.skillIdsOf(s), List.of(),
                scenarioService.docIdsOf(s), List.of(),
                scenarioService.knowledgeTagsOf(s), List.of(),
                project != null, true);
        ContextAssembler.AssembledContext a = assembler.assemble(req, s.getCode(), s.getName(),
                s.getExtraContextMd(), rendered);
        if (a == null) {
            return new ScenarioPreviewView(rendered, false, null, List.of(), List.of(), List.of(), 0, 0, null);
        }
        return new ScenarioPreviewView(rendered, true, a.pkg().claudeMd(), a.items(),
                a.pkg().skills().stream().map(sk -> sk.name()).toList(),
                a.pkg().docs().stream().map(d -> new ScenarioPreviewView.DocPreview(d.docId(), d.title())).toList(),
                a.manifest().entries(), a.manifest().totalBytes(), a.manifest().sha256());
    }
}
