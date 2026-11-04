// 扫描配置（FR-10）：项目内部 groupId 前缀（白名单过滤）+ 生效前缀预览。
import { Button, Input, Space, Tag, Typography, message } from 'antd'
import { useEffect, useState } from 'react'
import { getSecurityConfig, saveSecurityConfig } from '../api'
import { showError } from '../../../shared/utils/showError'
import { usePermissions } from '../../auth/usePermissions'

interface Props {
  projectId: string
  refreshTick: number
}

export default function ConfigView({ projectId, refreshTick }: Props) {
  const { canAction } = usePermissions()
  const canConfig = canAction('project.security.action.config')
  const [value, setValue] = useState('')
  const [effective, setEffective] = useState<string[]>([])
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    setLoading(true)
    getSecurityConfig(projectId)
      .then((c) => {
        setValue(c.internalGroupPrefixes ?? '')
        setEffective(c.effectivePrefixes ?? [])
      })
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [projectId, refreshTick])

  const onSave = async () => {
    setSaving(true)
    try {
      const c = await saveSecurityConfig(projectId, value.trim())
      setEffective(c.effectivePrefixes ?? [])
      message.success('已保存，下次扫描生效')
    } catch (e) {
      showError(e)
    } finally {
      setSaving(false)
    }
  }

  return (
    <div style={{ maxWidth: 720 }}>
      <Typography.Paragraph type="secondary">
        内部依赖（公司自研构件）默认不进漏洞统计。按段匹配前缀（com.apusic 不误伤 com.apusic2），逗号或换行分隔。
      </Typography.Paragraph>
      <Input.TextArea
        rows={4}
        value={value}
        onChange={(e) => setValue(e.target.value)}
        placeholder={'com.apusic\ncom.kingdee'}
        disabled={loading || !canConfig}
      />
      {canConfig && (
        <Button type="primary" style={{ marginTop: 12 }} loading={saving} onClick={onSave}>
          保存
        </Button>
      )}
      <Typography.Title level={5} style={{ marginTop: 24 }}>
        生效前缀（平台默认 + 项目追加）
      </Typography.Title>
      <Space size={4} wrap>
        {effective.length === 0 && <Typography.Text type="secondary">（未配置任何前缀）</Typography.Text>}
        {effective.map((p) => (
          <Tag key={p}>{p}</Tag>
        ))}
      </Space>
    </div>
  )
}
