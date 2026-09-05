import { Button, Card, Checkbox, Table, Tag, Typography, message } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { listRepos, setSubscription } from '../api'
import type { WorklogRepo } from '../types'
import { isAdmin } from '../../auth/authStore'

/**
 * CAP-28 FR-02 订阅页：勾选哪些全局仓库参与自己的 git log 扫描。
 * CAP-29 起登记 CRUD 移到后台 /admin/repos（仅 ADMIN），此页只保留勾选。
 */
export default function CodeReposPage() {
  const [rows, setRows] = useState<WorklogRepo[]>([])
  const [loading, setLoading] = useState(false)

  const reload = () => {
    setLoading(true)
    listRepos()
      .then(setRows)
      .catch((e) => message.error(`加载仓库失败: ${e.message}`))
      .finally(() => setLoading(false))
  }

  useEffect(reload, [])

  const toggle = async (r: WorklogRepo, checked: boolean) => {
    try {
      await setSubscription(r.id, checked)
      setRows(rows.map((x) => (x.id === r.id ? { ...x, subscribed: checked } : x)))
      message.success(checked ? `已勾选「${r.name}」，将参与你的 git 扫描` : `已取消勾选「${r.name}」`)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败')
    }
  }

  return (
    <Card
      title="代码仓库订阅"
      extra={
        <Button icon={<ReloadOutlined />} onClick={reload}>
          刷新
        </Button>
      }
    >
      <Typography.Paragraph type="secondary">
        勾选你参与的仓库后，git 扫描只按你的署名从这些仓库的服务端克隆取当日提交。
        {isAdmin() ? (
          <>
            仓库登记与维护在 <Link to="/admin/repos">后台管理 → 代码仓库</Link>。
          </>
        ) : (
          '需要新增仓库请联系管理员在后台登记。'
        )}
      </Typography.Paragraph>
      <Table
        rowKey="id"
        loading={loading}
        dataSource={rows}
        pagination={false}
        locale={{ emptyText: '暂无登记仓库，请联系管理员在后台「代码仓库」添加' }}
        columns={[
          {
            title: '参与扫描',
            width: 90,
            render: (_, r) => (
              <Checkbox checked={!!r.subscribed} onChange={(e) => toggle(r, e.target.checked)} />
            ),
          },
          { title: '名称', dataIndex: 'name', width: 180 },
          {
            title: '远端',
            dataIndex: 'remoteUrl',
            ellipsis: true,
            render: (u: string) => u || '-',
          },
          { title: '默认分支', dataIndex: 'defaultBranch', width: 110, render: (b: string) => b || '-' },
          {
            title: '克隆状态',
            dataIndex: 'cloneStatus',
            width: 100,
            render: (s: string) => {
              if (!s || s === 'NONE') return '-'
              const color =
                s === 'READY' ? 'success' : s === 'CLONING' ? 'processing' : s === 'FAILED' ? 'error' : 'default'
              return <Tag color={color}>{s}</Tag>
            },
          },
          {
            title: '状态',
            dataIndex: 'status',
            width: 90,
            render: (s: string) =>
              s === 'ACTIVE' ? <Tag color="green">启用</Tag> : <Tag>停用</Tag>,
          },
        ]}
      />
    </Card>
  )
}
