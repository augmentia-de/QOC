import { defineConfig } from 'vite'
import { resolve } from 'node:path'

export default defineConfig({
  base: '/core/',
  root: resolve(process.cwd(), 'core'),
  build: {
    outDir: resolve(process.cwd(), 'dist/core'),
    emptyOutDir: true,
  },
  resolve: {
    alias: {
      '@shared': resolve(process.cwd(), 'shared'),
    },
  },
  server: {
    fs: {
      allow: [resolve(process.cwd())],
    },
  },
})