import { fileURLToPath, URL } from 'node:url'

import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'
import { analysisMock } from './mock/analysisMock.ts'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  // `npm run dev:mock` (mode "mock") or VITE_MOCK=1 answers the analysis API from a scripted run.
  const mock = mode === 'mock' || loadEnv(mode, process.cwd(), 'VITE_').VITE_MOCK === '1'
  return {
    define: {
      'import.meta.env.VITE_MOCK': JSON.stringify(mock ? '1' : ''),
    },
    plugins: [
      vue(),
      vueDevTools(),
      mock && analysisMock(),
    ],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      proxy: {
        '/api': process.env.DATAPILOT_API ?? 'http://localhost:8080',
      },
    },
  }
})
