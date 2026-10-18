import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    // Caddy forwards https://guitar-mentor to this local Vite dev server.
    // Explicitly allow only our local alias; never use allowedHosts: true.
    allowedHosts: ['guitar-mentor'],
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: false,
      },
    },
  },
});
