import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 构建产物输出到默认 frontend/dist/，由 devmind-dist 打包为分发包的 web/ 目录（不再进 jar）
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/ws': { target: 'ws://localhost:8080', ws: true }
    }
  }
})
