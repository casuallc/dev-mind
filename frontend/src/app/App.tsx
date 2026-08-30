import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import AppLayout from './AppLayout'
// 各能力 feature 在此注册路由（积木式组装点）
import SessionsBoard from '../features/sessions/pages/SessionsBoard'
import SessionDetail from '../features/sessions/pages/SessionDetail'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<AppLayout />}>
          <Route path="/" element={<Navigate to="/sessions" replace />} />
          {/* CAP-05 会话 */}
          <Route path="/sessions" element={<SessionsBoard />} />
          <Route path="/sessions/:id" element={<SessionDetail />} />
          {/* 后续能力在此追加路由，如 CAP-02 项目、CAP-04 知识库 */}
        </Route>
      </Routes>
    </BrowserRouter>
  )
}
