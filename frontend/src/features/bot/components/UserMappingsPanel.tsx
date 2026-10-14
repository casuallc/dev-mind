// CAP-75 M3 用户映射面板：飞书 open_id ↔ 平台用户（user_platform_accounts 的 FEISHU IDENTITY 绑定）。
// 解绑走集成模块管理端点；解绑后该用户的敏感操作（/bind /session /build /deploy）恢复为门禁拒绝。
import { useCallback, useEffect, useState } from 'react'
import { Modal, Tag, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { adminUnbindPlatformAccount, listUserPlatformAccounts } from '../api'
import type { AdminPlatformAccount } from '../types'
import { fmtTime } from '../../../shared/utils/format'
import FitTable from '../../../shared/components/FitTable'
import { showError } from '../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../shared/utils/table'

export default function UserMappingsPanel({ refreshTick = 0 }: { refreshTick?: number }) {
  const [rows, setRows] = useState<AdminPlatformAccount[]>([])
  const [loading, setLoading] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setRows(await listUserPlatformAccounts('FEISHU'))
    } catch (e) {
      showError(e, '加载用户映射失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load, refreshTick])

  const onUnbind = (row: AdminPlatformAccount) => {
    Modal.confirm({
      title: `解绑 ${row.displayName || row.username} 的飞书身份？`,
      content: `身份码 ${row.externalUsername ?? '-'} 将被移除；该用户在飞书的敏感操作（/bind /session /build /deploy）将被门禁拒绝，问答不受影响。`,
      centered: true,
      okText: '解绑',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: () =>
        adminUnbindPlatformAccount(row.id)
          .then(() => {
            message.success('已解绑')
            load()
          })
          .catch((e) => showError(e, '解绑失败')),
    })
  }

  const columns: ColumnsType<AdminPlatformAccount> = [
    {
      title: '平台用户',
      width: 180,
      render: (_, r) => (
        <>
          {r.displayName || r.username}
          <Typography.Text type="secondary" style={{ marginLeft: 6, fontSize: 12 }}>
            {r.username}
          </Typography.Text>
        </>
      ),
    },
    {
      title: '飞书身份码（open_id）',
      dataIndex: 'externalUsername',
      width: 260,
      ellipsis: true,
      render: (s?: string) =>
        s ? <Typography.Text code copyable style={{ fontSize: 12 }}>{s}</Typography.Text> : '-',
    },
    { title: '集成实例', dataIndex: 'integrationName' },
    {
      title: '认证方式',
      dataIndex: 'authType',
      width: 110,
      render: (t?: string) => (t === 'IDENTITY' ? <Tag color="cyan">身份映射</Tag> : t || '-'),
    },
    { title: '绑定时间', dataIndex: 'createdAt', width: 160, render: (t) => fmtTime(t) },
    {
      title: '操作',
      width: 80,
      render: (_, r) => (
        <Typography.Link type="danger" onClick={() => onUnbind(r)}>
          解绑
        </Typography.Link>
      ),
    },
  ]

  return (
    <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
        飞书用户与平台账号的绑定关系（设置 → 第三方账号 的飞书身份码绑定）；绑定后该用户在飞书的敏感操作以其平台身份执行。
      </Typography.Paragraph>
      <FitTable<AdminPlatformAccount>
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={rows}
        pagination={LIST_PAGINATION}
        locale={{ emptyText: '暂无用户映射。用户在飞书给机器人发 /bindme 取身份码后，到 设置 → 第三方账号 绑定飞书集成。' }}
      />
    </div>
  )
}
