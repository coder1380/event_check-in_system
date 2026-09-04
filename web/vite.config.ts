import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': {
        target: 'https://event-checkin-backend-production-87a2.up.railway.app/api/v1',
        changeOrigin: true,
      },
      '/socket.io': {
        target: 'https://event-checkin-backend-production-87a2.up.railway.app/api/v1/',
        ws: true,
        changeOrigin: true,
      },
    },
  },
})
