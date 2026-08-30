import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 前后端一体：构建产物直接输出到 backend 静态目录，由 Spring Boot 托管
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/ws': { target: 'ws://localhost:8080', ws: true }
    }
  },
  build: {
    outDir: '../devmind-app/src/main/resources/static',
    emptyOutDir: true
  }
})
