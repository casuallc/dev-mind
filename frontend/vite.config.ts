import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 构建产物输出到默认 frontend/dist/，由 devmind-dist 打包为分发包的 web/ 目录（不再进 jar）
// 后端地址可用环境变量 VITE_BACKEND_URL 覆盖（默认本机），便于本地前端直连远程后端联调：
//   Git Bash:  VITE_BACKEND_URL=http://172.20.140.224:8080 npm run dev
//   PowerShell: $env:VITE_BACKEND_URL='http://172.20.140.224:8080'; npm run dev
const backend = process.env.VITE_BACKEND_URL || 'http://localhost:8080'

// 浏览器经非 localhost 地址（如 http://<本机IP>:5173）访问 dev server 时，透传的 Origin
// 不在后端 CORS / WebSocket 握手白名单会被 403。代理由 dev server 发起、天然同源语义，
// 摘掉 Origin 头规避（本机 localhost:5173 本就在白名单，摘掉同样无害）。
import type { ProxyOptions } from 'vite'

const stripOrigin: ProxyOptions['configure'] = (proxy) => {
  proxy.on('proxyReq', (proxyReq) => proxyReq.removeHeader('origin'))
}

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: backend, changeOrigin: true, configure: stripOrigin },
      '/ws': { target: backend.replace(/^http/, 'ws'), ws: true, configure: stripOrigin }
    }
  }
})
