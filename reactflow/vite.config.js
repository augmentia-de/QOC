import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { defineConfig } from 'vite'
import { resolve } from 'node:path'

const apiTarget = process.env.VITE_API_TARGET ?? 'http://localhost:8090';

export default defineConfig({
  base: '/',
  plugins: [react(), tailwindcss()],
  build: {
    outDir: resolve(process.cwd(), 'dist/app'),
    emptyOutDir: true,
  },
  resolve: {
    alias: {
      '@shared': resolve(process.cwd(), 'shared'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: apiTarget, changeOrigin: true },
    },
    fs: {
      allow: [resolve(process.cwd())],
    },
  },
})