import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发时 /api 与 /ota 代理到 Node 服务（默认 8787）；host:true 允许局域网其他设备访问
export default defineConfig({
  plugins: [vue()],
  server: {
    host: true,
    port: 5173,
    proxy: {
      '/api': 'http://127.0.0.1:8787',
      '/ota': 'http://127.0.0.1:8787',
    },
  },
})
