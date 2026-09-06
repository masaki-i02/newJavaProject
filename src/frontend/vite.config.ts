import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

/**
 * 認証はセッション Cookie + CSRF である。
 *
 * ★ 開発でも同一オリジンで配信する。
 *   CORS を開けると SameSite・credentials・プリフライトの三重苦になり、
 *   しかも「開発では通るが本番では通らない」経路ができる。
 *   バックエンドに CORS の設定が 1 行も無いのは、そういう意思表示である。
 */
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: false },
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});
