// 「关联工作单元」选择器：执行器（构建/部署/测试/发版）触发表单共用。
// 数据源为项目级只读端点 GET /projects/{pid}/work-items（WorkItemBrief 列表，seq 倒序）；
// 选中值即 workItemId，触发请求透传给后端做关联归集。
import { useEffect, useState } from 'react'
import { Select } from 'antd'
import { api } from '../api/client'
import type { WorkItemBrief } from '../types'

export default function WorkItemSelect({ projectId, value, onChange, disabled }: {
  projectId: string
  // value/onChange 可选：嵌进 Form.Item 时由 Form 注入，受控用法（执行器弹窗）显式传
  value?: string
  onChange?: (v: string | undefined, brief?: WorkItemBrief) => void
  disabled?: boolean
}) {
  const [items, setItems] = useState<WorkItemBrief[]>([])

  useEffect(() => {
    setItems([])
    api.get<WorkItemBrief[]>(`/projects/${projectId}/work-items`)
      .then(setItems)
      .catch(() => setItems([]))
  }, [projectId])

  return (
    <Select<string>
      style={{ width: '100%' }}
      showSearch
      allowClear
      disabled={disabled}
      placeholder="关联工作单元（可选，留空 = 项目级记录）"
      value={value}
      onChange={(v) => onChange?.(v, items.find((x) => x.id === v))}
      optionFilterProp="label"
      options={items.map((w) => ({
        value: w.id,
        label: `${w.code} ${w.title}${w.requirementCode ? `（${w.requirementCode}）` : ''}`,
      }))}
      notFoundContent={items.length ? undefined : '本项目暂无工作单元'}
    />
  )
}
