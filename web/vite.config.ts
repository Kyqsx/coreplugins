import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Em desenvolvimento (npm run dev) as chamadas /api vão para o license-server em 8080.
export default defineConfig({
  plugins: [react()],
  server: { proxy: { '/api': 'http://127.0.0.1:8080' } },
  build: { outDir: 'dist', emptyOutDir: true },
});
