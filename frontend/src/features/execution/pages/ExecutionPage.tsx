// CAP-36 执行底座页（/admin/execution）：命令模板白名单 + 执行审计。
// CAP-07 服务器运维（SSH/HTTP 直连）已下线——远程执行一律由 runner 节点承接（exec 帧），
// 服务端只渲染模板并调度；本页保留模板登记与执行留痕两个视图。
// 布局遵循 docs/core/前端内容区布局约定.md：Card 标题 + Segmented 切换视图，extra 随视图放操作按钮，表格默认密度。
import { useState } from 'react'
import { Button, Card, Segmented, Space, Typography } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import TemplatesTab from './TemplatesTab'
import AuditTab from './AuditTab'
import { pageCardStyle, pageCardBodyScrollStyle } from '../../../shared/utils/pageLayout'

const VIEW_DESC: Record<string, string> = {
  templates: '命令模板白名单：构建/部署/发版等执行器只允许使用项目内登记的模板，可按能力限定可用范围、声明参数 schema；模板在服务端渲染后随 exec 帧下发 runner 节点执行。',
  audit: '执行审计：经 exec 帧下发 runner 节点的每次执行全量留痕（命令为模板渲染结果，不含凭证），可按项目 / 节点 / 动作过滤。',
}

export default function ExecutionPage() {
  const [view, setView] = useState<string>('templates') // templates | audit
  // extra 按钮通过 tick 触发当前视图内组件的动作（刷新 / 新建模板）
  const [refreshTick, setRefreshTick] = useState(0)
  const [createTick, setCreateTick] = useState(0)

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyScrollStyle }}
      title={
        <Space size={12}>
          <span>模板与审计</span>
          <Segmented
            value={view}
            onChange={setView}
            options={[
              { value: 'templates', label: '命令模板' },
              { value: 'audit', label: '执行审计' },
            ]}
          />
        </Space>
      }
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => setRefreshTick((t) => t + 1)}>
            刷新
          </Button>
          {view === 'templates' && (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateTick((t) => t + 1)}>
              新建模板
            </Button>
          )}
        </Space>
      }
    >
      <Typography.Paragraph type="secondary">{VIEW_DESC[view]}</Typography.Paragraph>
      {view === 'templates' && <TemplatesTab refreshTick={refreshTick} createTick={createTick} />}
      {view === 'audit' && <AuditTab refreshTick={refreshTick} />}
    </Card>
  )
}
