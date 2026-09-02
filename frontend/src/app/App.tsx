import { BrowserRouter, Routes, Route, Navigate, useParams } from 'react-router-dom'
import { useEffect } from 'react'
import AppLayout from './AppLayout'
import AdminLayout from './AdminLayout'
// 各能力 feature 在此注册路由（积木式组装点）
import SessionsBoard from '../features/sessions/pages/SessionsBoard'
import SessionDetail from '../features/sessions/pages/SessionDetail'
import SessionTemplates from '../features/sessions/pages/SessionTemplates'
import ProjectsPage from '../features/projects/pages/ProjectsPage'
import ProjectOverviewPage from '../features/projects/pages/ProjectOverviewPage'
import RequirementsPage from '../features/requirements/pages/RequirementsPage'
import ProjectContextGate from './ProjectContextGate'
import RequirementDetailPage from '../features/requirements/pages/RequirementDetailPage'
import AdminProjectsPage from '../features/projects/pages/AdminProjectsPage'
import ProjectSettingsLayout from '../features/projects/pages/admin/ProjectSettingsLayout'
import ReposPage from '../features/projects/pages/admin/ReposPage'
import SummaryPage from '../features/projects/pages/admin/SummaryPage'
import ProjectServersPage from '../features/projects/pages/admin/ProjectServersPage'
import ProjectEnvironmentsPage from '../features/projects/pages/admin/ProjectEnvironmentsPage'
import BuildStepsPage from '../features/projects/pages/admin/BuildStepsPage'
import ReleaseConfigPage from '../features/projects/pages/admin/ReleaseConfigPage'
import JiraSyncPage from '../features/projects/pages/admin/JiraSyncPage'
import LockPage from '../features/projects/pages/admin/LockPage'
import BuildsPage from '../features/build/pages/BuildsPage'
import DeploymentsPage from '../features/deploy/pages/DeploymentsPage'
import TestsPage from '../features/test/pages/TestsPage'
import NotificationCenter from '../features/notifications/pages/NotificationCenter'
import KnowledgeBase from '../features/knowledge/pages/KnowledgeBase'
import DocsPage from '../features/docs/pages/DocsPage'
import DocEditorPage from '../features/docs/pages/DocEditorPage'
import ServersPage from '../features/server-adapter/pages/ServersPage'
import IntegrationsPage from '../features/integrations/pages/IntegrationsPage'
import ApiKeysPage from '../features/open-api/pages/ApiKeysPage'
import DashboardPage from '../features/dashboard/pages/DashboardPage'
import LoginPage from '../features/auth/pages/LoginPage'
import UserManagementPage from '../features/auth/pages/UserManagementPage'
import RequireAuth from '../features/auth/RequireAuth'
import RequireAdmin from '../features/auth/RequireAdmin'
import { setCurrentProject } from './currentProjectStore'

/** 旧链接兼容：/projects/:id → 同步当前项目后回概览（原 ProjectDetail 已拆成项目上下文菜单页） */
function LegacyProjectRedirect() {
  const { id } = useParams<{ id: string }>()
  useEffect(() => {
    if (id) setCurrentProject(id)
  }, [id])
  return <Navigate to="/overview" replace />
}

/** 旧链接兼容：/docs/:id → /admin/docs/:id（文档管理已迁入后台） */
function LegacyDocRedirect() {
  const { id } = useParams<{ id: string }>()
  return <Navigate to={`/admin/docs/${id}`} replace />
}

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
          <Route path="/" element={<Navigate to="/overview" replace />} />
          {/* 项目上下文页面（当前项目为主线，无项目时由 Gate 统一空态） */}
          <Route element={<ProjectContextGate />}>
            <Route path="/overview" element={<ProjectOverviewPage />} />
            <Route path="/requirements" element={<RequirementsPage />} />
            <Route path="/builds" element={<BuildsPage />} />
            <Route path="/deployments" element={<DeploymentsPage />} />
            <Route path="/tests" element={<TestsPage />} />
          </Route>
          {/* CAP-02 项目：列表仅切换器底部入口；详情页保留双参数 URL（可分享，进入时同步当前项目） */}
          <Route path="/projects" element={<ProjectsPage />} />
          <Route path="/projects/:id" element={<LegacyProjectRedirect />} />
          <Route path="/projects/:id/requirements/:rid" element={<RequirementDetailPage />} />
          {/* CAP-06 通知 */}
          <Route path="/notifications" element={<NotificationCenter />} />
          {/* CAP-05 会话 */}
          <Route path="/sessions" element={<SessionsBoard />} />
          <Route path="/sessions/:id" element={<SessionDetail />} />
          {/* 旧路径兼容：指挥中心/知识库/文档已迁入 /admin 后台 */}
          <Route path="/dashboard" element={<Navigate to="/admin/dashboard" replace />} />
          <Route path="/knowledge" element={<Navigate to="/admin/knowledge" replace />} />
          <Route path="/docs" element={<Navigate to="/admin/docs" replace />} />
          <Route path="/docs/:id" element={<LegacyDocRedirect />} />
          <Route path="/servers" element={<Navigate to="/admin/servers" replace />} />
          <Route path="/templates" element={<Navigate to="/admin/templates" replace />} />
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
          {/* CAP-16 指挥中心（全局视角，仅 ADMIN） */}
          <Route path="/admin/dashboard" element={<DashboardPage />} />
          {/* CAP-02 项目管理（增删改 + 项目配置；配置为嵌套子路由，各自独立加载） */}
          <Route path="/admin/projects" element={<AdminProjectsPage />} />
          <Route path="/admin/projects/:id" element={<ProjectSettingsLayout />}>
            <Route index element={<Navigate to="repos" replace />} />
            <Route path="repos" element={<ReposPage />} />
            <Route path="summary" element={<SummaryPage />} />
            <Route path="servers" element={<ProjectServersPage />} />
            <Route path="environments" element={<ProjectEnvironmentsPage />} />
            <Route path="build" element={<BuildStepsPage />} />
            <Route path="release" element={<ReleaseConfigPage />} />
            <Route path="jira" element={<JiraSyncPage />} />
            <Route path="lock" element={<LockPage />} />
          </Route>
          {/* CAP-01 用户管理 */}
          <Route path="/admin/users" element={<UserManagementPage />} />
          {/* CAP-07 服务器适配器 */}
          <Route path="/admin/servers" element={<ServersPage />} />
          {/* CAP-18/19 平台集成（GitLab / Jira） */}
          <Route path="/admin/integrations" element={<IntegrationsPage />} />
          {/* CAP-20 API 密钥（open-api HMAC 认证凭证） */}
          <Route path="/admin/keys" element={<ApiKeysPage />} />
          {/* CAP-05 会话模板 */}
          <Route path="/admin/templates" element={<SessionTemplates />} />
          {/* CAP-04 知识库 */}
          <Route path="/admin/knowledge" element={<KnowledgeBase />} />
          {/* CAP-03 文档 */}
          <Route path="/admin/docs" element={<DocsPage />} />
          <Route path="/admin/docs/:id" element={<DocEditorPage />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}
