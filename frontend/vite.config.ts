import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// API_TARGET is set to http://backend:8080 inside docker-compose
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { '/api': (globalThis as any).process?.env?.API_TARGET || 'http://localhost:8080' },
  },
})
