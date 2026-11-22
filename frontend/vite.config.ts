import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const { SERVER_PORT = '8080' } = loadEnv(mode, '.', 'SERVER_PORT')
  const proxy = { '/api': `http://127.0.0.1:${SERVER_PORT}` }
  const projectRoot = fileURLToPath(new URL('.', import.meta.url))

  return {
    plugins: [vue()],
    server: {
      host: '0.0.0.0',
      // 路径包含 ~ 时 Vite 的文件白名单校验会误判，本地开发放行项目目录
      fs: { strict: false, allow: [projectRoot] },
      proxy,
    },
    preview: { host: '0.0.0.0', proxy },
  }
})
