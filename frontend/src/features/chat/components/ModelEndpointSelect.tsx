// CAP-49：对话端点选择器（模型执行体专用）。
// 只列 kind=CHAT 且 active 的端点——向量端点拿去打 /chat/completions 是必坏的组合，
// 后端也会 400，所以压根不给选。预选平台默认（同类型唯一），状态里带出来。
import { Select, Tag } from 'antd'
import type { ModelEndpoint } from '../../model/types'

export default function ModelEndpointSelect({
  endpoints,
  value,
  onChange,
}: {
  endpoints: ModelEndpoint[]
  value?: number
  onChange?: (v?: number) => void
}) {
  return (
    <Select
      allowClear
      value={value}
      onChange={onChange}
      placeholder="跟随平台默认（后台 → 模型接入 里设为默认的那个）"
      options={endpoints.map((e) => ({
        value: e.id,
        label: (
          <span>
            {e.name}
            {e.model ? ` · ${e.model}` : ''}
            {e.isDefault && (
              <Tag color="blue" style={{ marginLeft: 6 }}>
                平台默认
              </Tag>
            )}
          </span>
        ),
      }))}
      notFoundContent="暂无可用对话端点（后台 → 模型接入）"
    />
  )
}
