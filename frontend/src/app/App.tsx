import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import AppLayout from './AppLayout'
import AdminLayout from './AdminLayout'
// 各能力 feature 在此注册路由（积木式组装点）
import SessionsBoard from '../features/sessions/pages/SessionsBoard'
import SessionDetail from '../features/sessions/pages/SessionDetail'
import SessionTemplates from '../features/sessions/pages/SessionTemplates'
import ProjectsPage from '../features/projects/pages/ProjectsPage'
import ProjectDetail from '../features/projects/pages/ProjectDetail'
import RequirementDetailPage from '../features/projects/pages/RequirementDetailPage'
import AdminProjectsPage from '../features/projects/pages/AdminProjectsPage'
import AdminProjectDetail from '../features/projects/pages/AdminProjectDetail'
import NotificationCenter from '../features/notifications/pages/NotificationCenter'
import KnowledgeBase from '../features/knowledge/pages/KnowledgeBase'
import DocsPage from '../features/docs/pages/DocsPage'
import DocEditorPage from '../features/docs/pages/DocEditorPage'
import ServersPage from '../features/server-adapter/pages/ServersPage'
import IntegrationsPage from '../features/integrations/pages/IntegrationsPage'
import DashboardPage from '../features/dashboard/pages/DashboardPage'
import LoginPage from '../features/auth/pages/LoginPage'
import UserManagementPage from '../features/auth/pages/UserManagementPage'
import RequireAuth from '../features/auth/RequireAuth'
import RequireAdmin from '../features/auth/RequireAdmin'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        {/* CAP-01 登录（裸路由，不带布局） */}
        <Route path="/login" element={<LoginPage />} />
        <Route
          element={
            <RequireAuth>
              <AppLayout />
            </RequireAuth>
          }
        >
          <Route path="/" element={<Navigate to="/dashboard" replace />} />
          {/* CAP-16 指挥中心 */}
          <Route path="/dashboard" element={<DashboardPage />} />
          {/* CAP-02 项目 */}
          <Route path="/projects" element={<ProjectsPage />} />
          <Route path="/projects/:id" element={<ProjectDetail />} />
          <Route path="/projects/:id/requirements/:rid" element={<RequirementDetailPage />} />
          {/* CAP-06 通知 */}
          <Route path="/notifications" element={<NotificationCenter />} />
          {/* CAP-04 知识库 */}
          <Route path="/knowledge" element={<KnowledgeBase />} />
          {/* CAP-03 文档 */}
          <Route path="/docs" element={<DocsPage />} />
          <Route path="/docs/:id" element={<DocEditorPage />} />
          {/* CAP-05 会话 */}
          <Route path="/sessions" element={<SessionsBoard />} />
          <Route path="/sessions/:id" element={<SessionDetail />} />
          {/* 旧路径兼容：管理功能已迁入 /admin 后台 */}
          <Route path="/settings" element={<Navigate to="/admin/users" replace />} />
          <Route path="/servers" element={<Navigate to="/admin/servers" replace />} />
          <Route path="/templates" element={<Navigate to="/admin/templates" replace />} />
          {/* 后续能力在此追加路由，如 CAP-04 知识库 */}
        </Route>
        {/* 管理后台（仅 ADMIN，RequireAdmin 守卫） */}
        <Route
          element={
            <RequireAdmin>
              <AdminLayout />
            </RequireAdmin>
          }
        >
          <Route path="/admin" element={<Navigate to="/admin/projects" replace />} />
          {/* CAP-02 项目管理（增删改 + 项目配置） */}
          <Route path="/admin/projects" element={<AdminProjectsPage />} />
          <Route path="/admin/projects/:id" element={<AdminProjectDetail />} />
          {/* CAP-01 用户管理 */}
          <Route path="/admin/users" element={<UserManagementPage />} />
          {/* CAP-07 服务器适配器 */}
          <Route path="/admin/servers" element={<ServersPage />} />
          {/* CAP-18/19 平台集成（GitLab / Jira） */}
          <Route path="/admin/integrations" element={<IntegrationsPage />} />
          {/* CAP-05 会话模板 */}
          <Route path="/admin/templates" element={<SessionTemplates />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}
