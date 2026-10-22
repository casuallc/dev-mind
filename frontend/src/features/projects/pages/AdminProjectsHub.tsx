// 项目管理聚合页（/admin/projects）：单一 Card，title 内 Segmented 切换六个视图（?tab= 驱动，URL 可分享）。
// 六个视图均为「项目管理」域下的收口入口：项目列表 / 经验提案（内嵌注入预览）/ 命令模板 / 代码库 / 场景 / 执行审计。
// 布局遵循 docs/core/前端内容区布局约定.md：Card title + Segmented（ScrollRow 包裹），extra 随视图切换，表格默认密度。
import { useState } from 'react'
import type { ReactNode } from 'react'
import { Button, Card, Segmented, Space } from 'antd'
import { BulbOutlined, PlusOutlined, ReloadOutlined, RobotOutlined } from '@ant-design/icons'
import { useSearchParams } from 'react-router-dom'
import ScrollRow from '../../../shared/components/ScrollRow'
import { pageCardBodyFlexStyle, pageCardStyle } from '../../../shared/utils/pageLayout'
import ProjectsView from './ProjectsView'
import ProposalsView from '../../knowledge/pages/ProposalsView'
import TemplatesTab from '../../execution/pages/TemplatesTab'
import AuditTab from '../../execution/pages/AuditTab'
import ReposView from '../../repos/pages/ReposView'
import ScenariosView from '../../scenarios/pages/ScenariosView'

const TABS = [
  { key: 'projects', label: '项目' },
  { key: 'proposals', label: '经验提案' },
  { key: 'templates', label: '命令模板' },
  { key: 'repos', label: '代码库' },
  { key: 'scenarios', label: '场景' },
  { key: 'audit', label: '执行审计' },
]

export default function AdminProjectsHub() {
  const [searchParams, setSearchParams] = useSearchParams()
  const rawTab = searchParams.get('tab') ?? 'projects'
  const view = TABS.some((t) => t.key === rawTab) ? rawTab : 'projects'

  // 统一 tick 驱动各视图的 extra 操作（同一时刻仅挂载一个视图，无跨视图串扰）
  const [refreshTick, setRefreshTick] = useState(0)
  const [createTick, setCreateTick] = useState(0)
  const [onboardTick, setOnboardTick] = useState(0)

  const setView = (v: string) => {
    setSearchParams(v === 'projects' ? {} : { tab: v }, { replace: true })
  }

  const refreshBtn = <Button icon={<ReloadOutlined />} onClick={() => setRefreshTick((t) => t + 1)}>刷新</Button>

  let extra: ReactNode = null
  if (view === 'projects') {
    extra = (
      <Space>
        {refreshBtn}
        <Button icon={<RobotOutlined />} onClick={() => setOnboardTick((t) => t + 1)}>
          AI 智能接入
        </Button>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateTick((t) => t + 1)}>
          新建项目
        </Button>
      </Space>
    )
  } else if (view === 'proposals') {
    extra = (
      <Space>
        {refreshBtn}
        <Button type="primary" icon={<BulbOutlined />} onClick={() => setCreateTick((t) => t + 1)}>
          手动沉淀经验
        </Button>
      </Space>
    )
  } else if (view === 'templates') {
    extra = (
      <Space>
        {refreshBtn}
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateTick((t) => t + 1)}>
          新建模板
        </Button>
      </Space>
    )
  } else if (view === 'repos') {
    extra = (
      <Space>
        {refreshBtn}
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateTick((t) => t + 1)}>
          登记仓库
        </Button>
      </Space>
    )
  } else if (view === 'scenarios') {
    extra = (
      <Space>
        {refreshBtn}
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateTick((t) => t + 1)}>
          新建场景
        </Button>
      </Space>
    )
  } else if (view === 'audit') {
    extra = <Space>{refreshBtn}</Space>
  }

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
      title={
        // 根节点必须 width:100% + minWidth:0：Card title 是 flex:1 + overflow:hidden，
        // ScrollRow 要靠这个确定的外边界计算可滚区间（Space 是 inline-flex 撑不满，不能用）
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, width: '100%', minWidth: 0 }}>
          <span style={{ flex: 'none' }}>项目管理</span>
          <ScrollRow activeSelector=".ant-segmented-item-selected">
            <Segmented
              value={view}
              onChange={(v) => setView(v as string)}
              options={TABS.map((t) => ({ value: t.key, label: t.label }))}
            />
          </ScrollRow>
        </div>
      }
      extra={extra}
    >
      {view === 'projects' && (
        <ProjectsView refreshTick={refreshTick} createTick={createTick} onboardTick={onboardTick} />
      )}
      {view === 'proposals' && <ProposalsView refreshTick={refreshTick} createTick={createTick} />}
      {view === 'templates' && <TemplatesTab refreshTick={refreshTick} createTick={createTick} />}
      {view === 'repos' && <ReposView refreshTick={refreshTick} createTick={createTick} />}
      {view === 'scenarios' && <ScenariosView refreshTick={refreshTick} createTick={createTick} />}
      {view === 'audit' && <AuditTab refreshTick={refreshTick} />}
    </Card>
  )
}
