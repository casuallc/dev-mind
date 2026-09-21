// 新建会话选项数据 hook（CAP-31：项目固定为「当前项目」，不再可选）：
// 场景（CAP-33）/执行节点/项目仓库/需求/工作单元的加载与级联联动，供 NewSessionDraft 高级选项使用。
import { useEffect, useState } from 'react'
import type { FormInstance } from 'antd'
import { Form } from 'antd'
import { listScenarios } from '../../scenarios/api'
import type { Scenario } from '../../scenarios/types'
import { listRepos } from '../../projects/api'
import type { ProjectRepo } from '../../projects/types'
import { listRequirements, listWorkItems } from '../../requirements/api'
import type { Requirement, WorkItem } from '../../requirements/types'
import { listAgentNodes } from '../../agent/api'
import type { AgentNode } from '../../agent/types'

export interface SessionOptionData {
  /** 可选场景：全局 + 当前项目的 PROJECT 场景（仅启用项） */
  scenarios: Scenario[]
  agentNodes: AgentNode[]
  /** 当前项目关联仓库（主库在前由后端保证 sortOrder；多选会话用） */
  repos: ProjectRepo[]
  requirements: Requirement[]
  workItems: WorkItem[]
}

export function useSessionOptionData(form: FormInstance, projectId: string | null): SessionOptionData {
  const [scenarios, setScenarios] = useState<Scenario[]>([])
  const [agentNodes, setAgentNodes] = useState<AgentNode[]>([])
  const [repos, setRepos] = useState<ProjectRepo[]>([])
  const [requirements, setRequirements] = useState<Requirement[]>([])
  const [workItems, setWorkItems] = useState<WorkItem[]>([])
  // preserve:true 必带：本 Form 住在 destroyOnHidden 的 Popover 里，弹层一关字段就卸载，
  // 默认口径把"字段卸载"读成"需求被清空"→ 下面的联动 effect 会误清刚选好的工作单元
  // （点一下输入框弹层就关，表现就是"选了工作单元，建会话时丢了"）
  const watchRequirementId = Form.useWatch('requirementId', { form, preserve: true })

  useEffect(() => {
    listScenarios(true)
      .then(setScenarios)
      .catch(() => undefined)
    listAgentNodes()
      .then(setAgentNodes)
      .catch(() => undefined)
  }, [])

  // 当前项目变化：加载其仓库与需求列表，清空已选关联（会话固定在当前项目下创建）
  useEffect(() => {
    form.setFieldsValue({ requirementId: undefined, workItemId: undefined, repoIds: undefined })
    setWorkItems([])
    if (!projectId) {
      setRepos([])
      setRequirements([])
      return
    }
    listRepos(projectId)
      .then(setRepos)
      .catch(() => setRepos([]))
    listRequirements(projectId, { size: 200 })
      // CAP-38 FR-06：关联需求自动建 WI，仅「可建 WI」的需求可选（排验收/完结/取消）
      .then((data) => setRequirements(
        data.items.filter((r) => !['ACCEPTANCE', 'DONE', 'CANCELLED'].includes(r.status))))
      .catch(() => setRequirements([]))
  }, [projectId, form])

  // 需求变化时加载其工作单元；切需求清空已选工作单元
  useEffect(() => {
    form.setFieldsValue({ workItemId: undefined })
    if (!projectId || !watchRequirementId) {
      setWorkItems([])
      return
    }
    listWorkItems(projectId, watchRequirementId)
      .then((ws) => setWorkItems(ws.filter((w) => !['DONE', 'CANCELLED'].includes(w.status))))
      .catch(() => setWorkItems([]))
  }, [projectId, watchRequirementId, form])

  const visibleScenarios = scenarios.filter(
    (s) => s.scope === 'GLOBAL' || (s.scope === 'PROJECT' && s.projectId === projectId),
  )

  return { scenarios: visibleScenarios, agentNodes, repos, requirements, workItems }
}
