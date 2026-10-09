// env 键值编辑器：套件定义（withSecret，secret 值回显掩码 '******'，原样回传=不变）
// 与触发覆盖（纯键值，仅本次生效）共用。作为 Form.Item 自定义控件（value/onChange）。
import { Button, Input, Switch, Tooltip } from 'antd'
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import type { ScriptSuiteEnv } from '../types'

export default function EnvEditor({ value, onChange, withSecret = true }: {
  value?: ScriptSuiteEnv[]
  onChange?: (v: ScriptSuiteEnv[]) => void
  withSecret?: boolean
}) {
  const rows = value ?? []
  const setRow = (i: number, patch: Partial<ScriptSuiteEnv>) => {
    onChange?.(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)))
  }
  const removeRow = (i: number) => onChange?.(rows.filter((_, j) => j !== i))
  const addRow = () => onChange?.([...rows, { key: '', value: '', secret: false }])

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      {rows.map((r, i) => (
        <div key={i} style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <Input
            style={{ width: 200 }}
            placeholder="变量名"
            value={r.key}
            onChange={(e) => setRow(i, { key: e.target.value })}
          />
          <Input
            style={{ flex: 1 }}
            placeholder="值"
            value={r.value ?? ''}
            onChange={(e) => setRow(i, { value: e.target.value })}
          />
          {withSecret && (
            <Tooltip title="敏感值：视图层恒显示掩码，掩码原样回传 = 该条不变">
              <span style={{ whiteSpace: 'nowrap', fontSize: 12, color: '#888' }}>
                <Switch
                  size="small"
                  checked={r.secret}
                  onChange={(v) => setRow(i, { secret: v })}
                  style={{ marginRight: 4 }}
                />
                脱敏
              </span>
            </Tooltip>
          )}
          <Button type="text" size="small" danger icon={<DeleteOutlined />} onClick={() => removeRow(i)} />
        </div>
      ))}
      <Button type="dashed" size="small" icon={<PlusOutlined />} onClick={addRow} style={{ alignSelf: 'flex-start' }}>
        添加变量
      </Button>
    </div>
  )
}
