import { defineConfig } from '@playwright/test';

/**
 * 通しのシナリオ（IT-SCN-32）。
 *
 * ★ 実物のバックエンドと PostgreSQL に対して通す。
 *   モックを置くと、原則 1（業務ルールを画面に複製しない）が守れているかを
 *   1 行も検査しない。サーバが返す `availableActions` と `canSubmit` を
 *   実際に受け取ることに意味がある。
 *
 * ★ ブラウザは環境に用意されているものを使う（`PLAYWRIGHT_BROWSERS_PATH`）。
 *   `playwright install` を実行しない。
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env['KINTAI_E2E_BASE_URL'] ?? 'http://localhost:5173',
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: {
        browserName: 'chromium',
        // ★ 環境に用意されている Chromium を直接指す。
        //   `@playwright/test` が要求するビルド番号と環境のビルド番号は一致しない。
        //   一致させようと `playwright install` を走らせると、
        //   この環境では取得できない（外向きの通信が塞がれている）。
        launchOptions: {
          executablePath: process.env['KINTAI_E2E_CHROMIUM']
            ?? '/opt/pw-browsers/chromium',
        },
      },
    },
  ],
});
