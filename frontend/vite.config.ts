import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const { SERVER_PORT = '8080' } = loadEnv(mode, '.', 'SERVER_PORT')
  const proxy = { '/api': `http://127.0.0.1:${SERVER_PORT}` }

  return {
    plugins: [vue()],
    server: { proxy },
    preview: { proxy },
  }
})
