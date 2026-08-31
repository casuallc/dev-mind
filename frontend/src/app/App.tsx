import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import AppLayout from './AppLayout'
// 各能力 feature 在此注册路由（积木式组装点）
import SessionsBoard from '../features/sessions/pages/SessionsBoard'
import SessionDetail from '../features/sessions/pages/SessionDetail'
import SessionTemplates from '../features/sessions/pages/SessionTemplates'
import ProjectsPage from '../features/projects/pages/ProjectsPage'
import ProjectDetail from '../features/projects/pages/ProjectDetail'
import NotificationCenter from '../features/notifications/pages/NotificationCenter'
import KnowledgeBase from '../features/knowledge/pages/KnowledgeBase'
import DocsPage from '../features/docs/pages/DocsPage'
import DocEditorPage from '../features/docs/pages/DocEditorPage'
import ServersPage from '../features/server-adapter/pages/ServersPage'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<AppLayout />}>
          <Route path="/" element={<Navigate to="/sessions" replace />} />
          {/* CAP-02 项目 */}
          <Route path="/projects" element={<ProjectsPage />} />
          <Route path="/projects/:id" element={<ProjectDetail />} />
          {/* CAP-06 通知 */}
          <Route path="/notifications" element={<NotificationCenter />} />
          {/* CAP-04 知识库 */}
          <Route path="/knowledge" element={<KnowledgeBase />} />
          {/* CAP-03 文档 */}
          <Route path="/docs" element={<DocsPage />} />
          <Route path="/docs/:id" element={<DocEditorPage />} />
          {/* CAP-07 服务器适配器 */}
          <Route path="/servers" element={<ServersPage />} />
          {/* CAP-05 会话 */}
          <Route path="/sessions" element={<SessionsBoard />} />
          <Route path="/sessions/:id" element={<SessionDetail />} />
          <Route path="/templates" element={<SessionTemplates />} />
          {/* 后续能力在此追加路由，如 CAP-04 知识库 */}
        </Route>
      </Routes>
    </BrowserRouter>
  )
}
