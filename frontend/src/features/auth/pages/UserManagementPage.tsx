import { Button, Card, Segmented, Space } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import UsersPanel from '../components/UsersPanel'
import RolesPanel from '../components/RolesPanel'
import { pageCardBodyFlexStyle, pageCardStyle } from '../../../shared/utils/pageLayout'
import { usePermissions, usePermittedView } from '../usePermissions'

type UserTab = 'users' | 'roles'
const USER_TABS: UserTab[] = ['users', 'roles']
const USER_TAB_LABELS: Record<UserTab, string> = { users: '用户', roles: '角色' }
const USER_TAB_PERMISSIONS: Record<UserTab, string> = {
  users: 'admin.users.tab.users',
  roles: 'admin.users.tab.roles',
}
const USER_TAB_CREATE_ACTION: Record<UserTab, string> = {
  users: 'admin.users.action.manage-user',
  roles: 'admin.users.action.manage-role',
}

/** CAP-01/CAP-102 后台页：用户管理（用户 / 角色两个页内视图，角色配置菜单权限）。 */
export default function UserManagementPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const tab: UserTab = searchParams.get('tab') === 'roles' ? 'roles' : 'users'
  const [refreshTick, setRefreshTick] = useState(0)
  const [createOpen, setCreateOpen] = useState(false)
  const { canAction } = usePermissions()

  const setTab = (next: UserTab) => {
    setCreateOpen(false)
    setSearchParams(next === 'roles' ? { tab: 'roles' } : {}, { replace: true })
  }
  const tabs = usePermittedView(USER_TABS, (t) => USER_TAB_PERMISSIONS[t], tab, setTab)

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
      title={
        <Space size={12}>
          <span>用户管理</span>
          <Segmented
            value={tab}
            onChange={(v) => setTab(v as UserTab)}
            options={tabs.map((t) => ({ value: t, label: USER_TAB_LABELS[t] }))}
          />
        </Space>
      }
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => setRefreshTick((t) => t + 1)}>
            刷新
          </Button>
          {canAction(USER_TAB_CREATE_ACTION[tab]) && (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
              {tab === 'users' ? '新建用户' : '新建角色'}
            </Button>
          )}
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
