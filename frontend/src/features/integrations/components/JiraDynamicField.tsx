// CAP-47 FR-08 动态字段的取值换算与输入控件。
// 推送弹窗（requirements）与项目推送默认值配置页（integrations）都要按 control 渲染同一批字段，
// 且配置页存下的取值要能被弹窗读回——两处各写一份必然漂移，故收敛到这里：
//   toJiraValue   表单形态 → Jira API 形态（提交/保存时用）
//   fromJiraValue Jira API 形态 → 表单形态（回填配置时用，与 toJiraValue 互为逆）
// control 由服务端按 Jira schema 判定，这里只做控件映射，不猜 Jira 内部结构。
import { DatePicker, Form, Input, InputNumber, Select, Space } from 'antd'
import dayjs from 'dayjs'
import type { JiraCreateField } from '../types'

/**
 * 表单值 → 平台取值。取值形态由字段类型决定（枚举回传 id、日期回传字符串…）。
 * 返回 undefined 表示「没填」，调用方据此不写进 payload（与固定字段同口径）。
 */
export function toJiraValue(f: JiraCreateField, v: unknown): unknown {
  switch (f.control) {
    case 'MULTI_SELECT': {
      const list = (Array.isArray(v) ? v : []).filter(
        (x): x is string => typeof x === 'string' && !!x.trim(),
      )
      if (!list.length) return undefined
      // 枚举类传 id（模块/版本/选项）；无候选的自由文本数组原样传字符串
      return f.options.length ? list.map((id) => ({ id })) : list
    }
    case 'SELECT':
      return typeof v === 'string' && v ? { id: v } : undefined
    case 'DATE':
      return v ? (v as dayjs.Dayjs).format('YYYY-MM-DD') : undefined
    case 'NUMBER':
      return typeof v === 'number' ? v : undefined
    case 'TIMETRACKING': {
      const t = (v ?? {}) as { originalEstimate?: string; remainingEstimate?: string }
      const original = t.originalEstimate?.trim()
      const remaining = t.remainingEstimate?.trim()
      if (!original && !remaining) return undefined
      // 剩余估算留空时取初始预估同值：Jira 自己的创建页就是这个默认，
      // 而该字段两项都被标必填时只传一项同样会被拒
      return { originalEstimate: original, remainingEstimate: remaining || original }
    }
    default:
      return typeof v === 'string' && v.trim() ? v.trim() : undefined
  }
}

/**
 * 平台取值 → 表单值（{@link toJiraValue} 的逆向）。控件类型未知/取值形态对不上时返回
 * undefined——读不回来的旧配置当没配，好过把 `{id}` 对象塞进 Select 显示成乱码。
 */
export function fromJiraValue(f: JiraCreateField, v: unknown): unknown {
  switch (f.control) {
    case 'MULTI_SELECT': {
      const list = Array.isArray(v) ? v : []
      const ids = list.map((x) => {
        if (typeof x === 'string') return x
        if (x && typeof x === 'object' && typeof (x as { id?: unknown }).id === 'string') {
          return (x as { id: string }).id
        }
        return null
      }).filter((x): x is string => x !== null)
      return ids.length ? ids : undefined
    }
    case 'SELECT': {
      if (typeof v === 'string') return v
      const id = v && typeof v === 'object' ? (v as { id?: unknown }).id : null
      return typeof id === 'string' ? id : undefined
    }
    case 'DATE':
      return typeof v === 'string' && v ? dayjs(v) : undefined
    case 'NUMBER':
      return typeof v === 'number' ? v : undefined
    case 'TIMETRACKING':
      return v && typeof v === 'object' ? v : undefined
    default:
      return typeof v === 'string' && v ? v : undefined
  }
}

/**
 * 动态字段输入项。control 由服务端按 Jira schema 判定，这里只做控件映射；
 * 时间跟踪是唯一的多值字段（初始预估 + 剩余估算），单独一组输入。
 *
 * <p>{@code required=false}（默认值配置页）：这些字段是「预填模板」，留空即不预填，
 * 不该按 Jira 的必填约束拦住保存——真正必填与否由推送时该任务类型的 createmeta 决定。
 */
export default function JiraDynamicField({ field, required = true }: {
  field: JiraCreateField
  required?: boolean
}) {
  const options = field.options.map((o) => ({ value: o.id ?? o.name, label: o.name }))
  const rules = required ? [{ required: true, message: `请填写${field.name}` }] : []
  if (field.control === 'TIMETRACKING') {
    return (
      <Form.Item label={field.name} required={required} tooltip="Jira 时长格式，如 2h、30m、1d 4h">
        <Space.Compact style={{ width: '100%' }}>
          <Form.Item name={['extraFields', field.id, 'originalEstimate']} noStyle
            rules={required ? [{ required: true, message: '请填写初始预估' }] : []}>
            <Input placeholder="初始预估（如 2h）" />
          </Form.Item>
          <Form.Item name={['extraFields', field.id, 'remainingEstimate']} noStyle>
            <Input placeholder="剩余估算（留空取初始预估）" />
          </Form.Item>
        </Space.Compact>
      </Form.Item>
    )
  }
  return (
    <Form.Item label={field.name} name={['extraFields', field.id]} rules={rules}>
      {field.control === 'MULTI_SELECT' ? (
        options.length
          ? <Select mode="multiple" optionFilterProp="label" placeholder="可多选" options={options} />
          // 无候选的自由文本数组（自定义的标签类字段）
          : <Select mode="tags" open={false} placeholder="回车添加" />
      ) : field.control === 'SELECT' ? (
        <Select allowClear placeholder="请选择" optionFilterProp="label" options={options} />
      ) : field.control === 'DATE' ? (
        <DatePicker style={{ width: '100%' }} />
      ) : field.control === 'NUMBER' ? (
        <InputNumber style={{ width: '100%' }} placeholder="请填写数字" />
      ) : (
        <Input placeholder={required ? '请填写' : '留空则不预填'} />
      )}
    </Form.Item>
  )
}
