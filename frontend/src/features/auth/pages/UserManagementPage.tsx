import { Button, Card, Segmented, Space } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import UsersPanel from '../components/UsersPanel'
import RolesPanel from '../components/RolesPanel'
import { pageCardBodyFlexStyle, pageCardStyle } from '../../../shared/utils/pageLayout'

/** CAP-01/CAP-102 后台页：用户管理（用户 / 角色两个页内视图，角色配置菜单权限）。 */
export default function UserManagementPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const tab = searchParams.get('tab') === 'roles' ? 'roles' : 'users'
  const [refreshTick, setRefreshTick] = useState(0)
  const [createOpen, setCreateOpen] = useState(false)

  const setTab = (next: string) => {
    setCreateOpen(false)
    setSearchParams(next === 'roles' ? { tab: 'roles' } : {}, { replace: true })
  }

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
      title={
        <Space size={12}>
          <span>用户管理</span>
          <Segmented
            value={tab}
            onChange={setTab}
            options={[
              { value: 'users', label: '用户' },
              { value: 'roles', label: '角色' },
            ]}
          />
        </Space>
      }
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => setRefreshTick((t) => t + 1)}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
            {tab === 'users' ? '新建用户' : '新建角色'}
          </Button>
        </Space>
      }
    >
      {tab === 'users' ? (
        <UsersPanel
          createOpen={createOpen}
          onCreateOpenChange={setCreateOpen}
          refreshTick={refreshTick}
        />
      ) : (
        <RolesPanel
          createOpen={createOpen}
          onCreateOpenChange={setCreateOpen}
          refreshTick={refreshTick}
        />
      )}
    </Card>
  )
}
