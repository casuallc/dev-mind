import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 构建产物输出到默认 frontend/dist/，由 devmind-dist 打包为分发包的 web/ 目录（不再进 jar）
// 后端地址可用环境变量 VITE_BACKEND_URL 覆盖（默认本机），便于本地前端直连远程后端联调：
//   Git Bash:  VITE_BACKEND_URL=http://172.20.140.224:8080 npm run dev
//   PowerShell: $env:VITE_BACKEND_URL='http://172.20.140.224:8080'; npm run dev
// 注意：经非 localhost 地址访问 dev server 时，浏览器 Origin 须在后端 devmind.cors.allowed-origins 白名单内，否则 403
const backend = process.env.VITE_BACKEND_URL || 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: backend, changeOrigin: true },
      '/ws': { target: backend.replace(/^http/, 'ws'), ws: true }
    }
  }
})
