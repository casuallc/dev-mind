// 新建会话选项数据 hook：项目/模板/执行节点/需求/工作单元的加载与级联联动，
// 从 SessionsBoard 抽取，供 NewSessionDraft 高级选项等复用。
import { useEffect, useState } from 'react'
import type { FormInstance } from 'antd'
import { Form } from 'antd'
import { listTemplates } from '../api'
import type { SessionTemplate } from '../types'
import { listProjects } from '../../projects/api'
import type { Project } from '../../projects/types'
import { listRequirements, listWorkItems } from '../../requirements/api'
import type { Requirement, WorkItem } from '../../requirements/types'
import { listAgentNodes } from '../../agent/api'
import type { AgentNode } from '../../agent/types'

export interface SessionOptionData {
  projects: Project[]
  templates: SessionTemplate[]
  agentNodes: AgentNode[]
  requirements: Requirement[]
  workItems: WorkItem[]
}

export function useSessionOptionData(form: FormInstance): SessionOptionData {
  const [projects, setProjects] = useState<Project[]>([])
  const [templates, setTemplates] = useState<SessionTemplate[]>([])
  const [agentNodes, setAgentNodes] = useState<AgentNode[]>([])
  const [requirements, setRequirements] = useState<Requirement[]>([])
  const [workItems, setWorkItems] = useState<WorkItem[]>([])
  const watchProjectId = Form.useWatch('projectId', form)
  const watchRequirementId = Form.useWatch('requirementId', form)

  useEffect(() => {
    listTemplates()
      .then(setTemplates)
      .catch(() => undefined)
    listAgentNodes()
      .then(setAgentNodes)
      .catch(() => undefined)
    listProjects('ACTIVE')
      .then((ps) => {
        setProjects(ps)
        // 默认选中种子项目 default；不存在则选第一个
        if (!ps.some((p) => p.id === 'default') && ps.length > 0 && !form.getFieldValue('projectId')) {
          form.setFieldsValue({ projectId: ps[0].id })
        }
      })
      .catch(() => undefined)
  }, [form])

  // 项目变化时加载其需求列表（会话可挂到工作单元/需求主线上）；切换项目清空已选关联
  useEffect(() => {
    form.setFieldsValue({ requirementId: undefined, workItemId: undefined })
    setWorkItems([])
    if (!watchProjectId) {
      setRequirements([])
      return
    }
    listRequirements(watchProjectId, { size: 200 })
      .then((data) => setRequirements(data.items.filter((r) => !['DONE', 'CANCELLED'].includes(r.status))))
      .catch(() => setRequirements([]))
  }, [watchProjectId, form])

  // 需求变化时加载其工作单元；切需求清空已选工作单元
  useEffect(() => {
    form.setFieldsValue({ workItemId: undefined })
    if (!watchProjectId || !watchRequirementId) {
      setWorkItems([])
      return
    }
    listWorkItems(watchProjectId, watchRequirementId)
      .then((ws) => setWorkItems(ws.filter((w) => !['DONE', 'CANCELLED'].includes(w.status))))
      .catch(() => setWorkItems([]))
  }, [watchProjectId, watchRequirementId, form])

  return { projects, templates, agentNodes, requirements, workItems }
}
