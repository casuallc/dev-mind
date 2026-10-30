// CAP-56 决策实验室页（/admin/laya/lab）：评测集 / 评测运行 / 微调任务 / 产物登记 四视图。
// 外壳只管视图切换与 extra 按钮（经 tick 传给视图内组件），视图内容各自自包含。
// 布局遵循 docs/core/前端内容区布局约定.md：Card 标题 + Segmented 切换视图，extra 随视图放操作按钮，
// 表格默认密度并走 FitTable（表体内部滚动）。
import { useState } from 'react'
import { Button, Card, Segmented, Space, Typography } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import ScrollRow from '../../../shared/components/ScrollRow'
import { pageCardBodyFlexStyle, pageCardStyle } from '../../../shared/utils/pageLayout'
import DatasetView from '../components/DatasetView'
import EvaluationView from '../components/EvaluationView'
import FinetuneView from '../components/FinetuneView'
import CheckpointView from '../components/CheckpointView'
import { usePermissions, usePermittedView } from '../../auth/usePermissions'

type LabView = 'datasets' | 'evaluations' | 'finetunes' | 'checkpoints'

const LAB_VIEWS: LabView[] = ['datasets', 'evaluations', 'finetunes', 'checkpoints']
const LAB_VIEW_LABELS: Record<LabView, string> = {
  datasets: '评测集',
  evaluations: '评测运行',
  finetunes: '微调任务',
  checkpoints: 'Checkpoint 登记',
}
const LAB_VIEW_PERMISSIONS: Record<LabView, string> = {
  datasets: 'admin.laya-lab.tab.datasets',
  evaluations: 'admin.laya-lab.tab.evaluations',
  finetunes: 'admin.laya-lab.tab.finetunes',
  checkpoints: 'admin.laya-lab.tab.checkpoints',
}
const LAB_VIEW_ACTIONS: Record<LabView, string> = {
  datasets: 'admin.laya-lab.action.manage-dataset',
  evaluations: 'admin.laya-lab.action.manage-evaluation',
  finetunes: 'admin.laya-lab.action.manage-finetune',
  checkpoints: 'admin.laya-lab.action.manage-checkpoint',
}

const VIEW_DESC: Record<LabView, string> = {
  datasets:
    '评测集：一套固定的题面 + 人工裁决（gold）。冻结后才能用于评测——冻结时强制要求三类对照组（空召回 / 逐字重复 / 不相关）缺一不可，因为「模型是不是恒答同一个答案」只有摊开对照组才看得见。要改已冻结的集请用「修订为新版本」。',
  evaluations:
    '评测运行：在节点上跑一次评测，报告里永远带两条基线（随机 / 多数类）——没有基线，「准确率 0.72」读不出好坏。给了对照 checkpoint 就出逐题胜负，勾了温度校准就报校准前后的 ECE。',
  finetunes:
    '微调任务：用回流集（线上已裁决的记录收编而成）在 GPU 节点上跑 RLCD 微调。权重留在节点，平台只收指标与指纹；训练结束会自动登记产物并在回评集上跑一次评测——「训练成功」不等于「学得更好」，要看回评。',
  checkpoints:
    '产物登记与准入闸门：登记官方基础模型或微调产物（来源路径必填），跑一次 serve 自检核对「登记的那份 == 边车正在服务的那份」，再人工放行。没有任何已放行的产物时，消费方（知识库分诊）整体不可用。',
}

const VIEW_ACTION: Record<LabView, string> = {
  datasets: '新建评测集',
  evaluations: '发起评测',
  finetunes: '发起微调',
  checkpoints: '登记产物',
}

export default function DecisionLabPage() {
  const { canAction } = usePermissions()
  const [view, setView] = useState<LabView>('datasets')
  const views = usePermittedView(LAB_VIEWS, (v) => LAB_VIEW_PERMISSIONS[v], view, setView)
  const canCreate = canAction(LAB_VIEW_ACTIONS[view])
  // extra 按钮通过 tick 触发当前视图内组件的动作（刷新 / 新建）
  const [refreshTick, setRefreshTick] = useState(0)
  const [createTick, setCreateTick] = useState(0)

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
      title={
        // 根节点 width:100% + minWidth:0：Card title 是 flex:1 + overflow:hidden，
        // ScrollRow 要靠这个确定的外边界算可滚区间（Space 是 inline-flex 撑不满，不能用）
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, width: '100%', minWidth: 0 }}>
          <span style={{ flex: 'none' }}>决策实验室</span>
          <ScrollRow activeSelector=".ant-segmented-item-selected">
            <Segmented
              value={view}
              onChange={(v) => setView(v as LabView)}
              options={views.map((v) => ({ value: v, label: LAB_VIEW_LABELS[v] }))}
            />
          </ScrollRow>
        </div>
      }
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => setRefreshTick((t) => t + 1)}>
            刷新
          </Button>
          {canCreate && (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateTick((t) => t + 1)}>
              {VIEW_ACTION[view]}
            </Button>
          )}
        </Space>
      }
    >
      <Typography.Paragraph type="secondary">{VIEW_DESC[view]}</Typography.Paragraph>
      {view === 'datasets' && <DatasetView refreshTick={refreshTick} createTick={createTick} />}
      {view === 'evaluations' && <EvaluationView refreshTick={refreshTick} createTick={createTick} />}
      {view === 'finetunes' && <FinetuneView refreshTick={refreshTick} createTick={createTick} />}
      {view === 'checkpoints' && <CheckpointView refreshTick={refreshTick} createTick={createTick} />}
    </Card>
  )
}
