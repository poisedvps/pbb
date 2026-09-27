import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    // 本地开发时把 /api 转发到本地后端
    proxy: { '/api': 'http://localhost:8080' }
  }
})
