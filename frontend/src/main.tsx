import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
// React 19 兼容补丁：修复 antd v5 静态方法（Modal.confirm/message/notification）不渲染
import '@ant-design/v5-patch-for-react-19'
import 'dayjs/locale/zh-cn'
import App from './app/App'
import './index.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ConfigProvider locale={zhCN}>
      <App />
    </ConfigProvider>
  </StrictMode>,
)
