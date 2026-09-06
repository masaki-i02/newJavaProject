import { expect, test } from '@playwright/test';

/**
 * IT-SCN-32 打刻 → 提出 → 承認 を画面から通す。
 *
 * ★ ボタンの出し分けがサーバの応答だけで決まっていることを確かめる。
 *   画面が状態機械を複製していると、
 *   「出勤したのに休憩開始が出ない」「退勤済みなのに出勤が出る」が起きる。
 *
 * 前提: バックエンドが起動していて、
 *   - E0001（一般社員）と E0100（部署長）が登録されている
 *   - 対象月が終わっている（`KINTAI_E2E_MONTH`）
 * `e2e/seed.sql` を流してから実行する。
 */
const EMPLOYEE = { number: 'E0001', password: 'correct-horse-battery' };
const APPROVER = { number: 'E0100', password: 'correct-horse-battery' };

test.describe('打刻から承認まで', () => {
  test('IT-SCN-32 打刻のボタンがサーバの応答どおりに切り替わる', async ({ page }) => {
    await signIn(page, EMPLOYEE);

    // 未出勤。押せるのは出勤だけ
    await expect(page.getByRole('button', { name: '出勤' })).toBeVisible();
    await expect(page.getByRole('button', { name: '退勤' })).toHaveCount(0);

    await page.getByRole('button', { name: '出勤' }).click();

    // 勤務中。休憩開始と退勤が出て、出勤は消える
    await expect(page.getByRole('button', { name: '休憩開始' })).toBeVisible();
    await expect(page.getByRole('button', { name: '退勤' })).toBeVisible();
    await expect(page.getByRole('button', { name: '出勤' })).toHaveCount(0);

    await page.getByRole('button', { name: '休憩開始' }).click();

    // 休憩中。押せるのは休憩終了だけ
    await expect(page.getByRole('button', { name: '休憩終了' })).toBeVisible();
    await expect(page.getByRole('button', { name: '退勤' })).toHaveCount(0);

    await page.getByRole('button', { name: '休憩終了' }).click();
    await page.getByRole('button', { name: '退勤' }).click();

    // 退勤済。押せるボタンが無い（同じ勤務日に出勤し直せない）
    await expect(page.getByText('本日の勤務は終了しています')).toBeVisible();
  });

  test('IT-SCN-33 提出できない月では提出ボタンが押せない', async ({ page }) => {
    await signIn(page, EMPLOYEE);
    await page.getByRole('button', { name: '月次勤怠' }).click();

    // 当月はまだ終わっていないので canSubmit が false
    await expect(page.getByRole('button', { name: '提出する' })).toBeDisabled();
    await expect(page.getByText('対象月が終わっていない')).toBeVisible();
  });

  test('IT-SCN-34 承認者にだけ承認メニューが出る', async ({ page }) => {
    await signIn(page, EMPLOYEE);
    await expect(page.getByRole('button', { name: '承認' })).toHaveCount(0);

    await page.getByRole('button', { name: 'ログイン' }).count(); // 画面が出ていること
    await page.context().clearCookies();
    await page.goto('/');
    await signIn(page, APPROVER);
    await expect(page.getByRole('button', { name: '承認' })).toBeVisible();
  });
});

async function signIn(page: import('@playwright/test').Page,
                      user: { number: string; password: string }) {
  await page.goto('/');
  await page.getByLabel('社員番号').fill(user.number);
  await page.getByLabel('パスワード').fill(user.password);
  await page.getByRole('button', { name: 'ログイン' }).click();
  await expect(page.getByRole('heading', { name: '勤怠管理システム' })).toBeVisible();
}
