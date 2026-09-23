// 智能决策（laya）页内视图切换器（布局约定：多视图切换放 Card title，禁 Card 内套 Tabs）。
// 「决策记录 ↔ 在线试分类」、「服务实例 ↔ 三类安装包（边车程序/模型权重/语料数据）」；
// 切换 = 路由跳转（URL 可分享），各页保留自己的 Card 与 extra 操作按钮。参考实现 = SettingsViewSwitch。
import type { ReactNode } from 'react'
import { Segmented } from 'antd'
import { useNavigate } from 'react-router-dom'
import ScrollRow from '../../../shared/components/ScrollRow'

const GROUPS = {
  records: {
    title: '决策记录',
    options: [
      { value: 'records', label: '决策记录', path: '/admin/laya/records' },
      { value: 'playground', label: '在线试分类', path: '/admin/laya/playground' },
    ],
  },
  instances: {
    title: '服务实例',
    options: [
      { value: 'instances', label: '服务实例', path: '/admin/laya/instances' },
      // 三类安装包直接作页内视图（value = 后端的 ClassifyPackageKind，安装包页从 ?kind= 读取）
      { value: 'SIDECAR_APP', label: '边车程序包', path: '/admin/laya/packages?kind=SIDECAR_APP' },
      { value: 'MODEL_WEIGHTS', label: '模型权重包', path: '/admin/laya/packages?kind=MODEL_WEIGHTS' },
      { value: 'CORPUS', label: '语料/数据包', path: '/admin/laya/packages?kind=CORPUS' },
    ],
  },
} as const

export type LayaViewGroup = keyof typeof GROUPS

export default function LayaViewSwitch({
  group,
  value,
  children,
}: {
  group: LayaViewGroup
  value: string
  /** 页面自己的头部控件（如试分类的通道选择、安装包的类型筛选），跟在 Segmented 后面 */
  children?: ReactNode
}) {
  const navigate = useNavigate()
  const g = GROUPS[group]
  return (
    // 根节点必须 width:100% + minWidth:0：Card title 是 flex:1 + overflow:hidden，
    // ScrollRow 要靠这个确定的外边界计算可滚区间（Space 是 inline-flex 撑不满，不能用）
    <div style={{ display: 'flex', alignItems: 'center', gap: 12, width: '100%', minWidth: 0 }}>
      <span style={{ flex: 'none' }}>{g.title}</span>
      <ScrollRow activeSelector=".ant-segmented-item-selected">
        <Segmented
          value={value}
          onChange={(v) => {
            const opt = g.options.find((o) => o.value === v)
            if (opt) navigate(opt.path)
          }}
          options={g.options.map((o) => ({ value: o.value, label: o.label }))}
        />
      </ScrollRow>
      {children}
    </div>
  )
}
