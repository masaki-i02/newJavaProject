import { expect, test, type Page } from '@playwright/test';

/**
 * 人事の 3 画面（SC-08 月次締め / SC-10 就業規則 / SC-11 会社カレンダー）。
 *
 * ★ 実物のバックエンドへ当てる。**手書きの API の型はコンパイラに守られていない**
 *   （落とし穴 143）。項目名を取り違えても型検査もフロントの単体テストも通り、
 *   画面は `undefined` を読んで `NaN` を出したまま動き続ける（落とし穴 151）。
 *   実際に叩いて初めて出る。
 *
 * 前提: `e2e/seed.sql` が入っていること（`globalSetup` が毎回入れ直す）。
 */
const HR = { number: 'E0900', password: 'correct-horse-battery' };
const EMPLOYEE = { number: 'E0001', password: 'correct-horse-battery' };

test.describe('人事の画面', () => {
  /**
   * ★ メニューの出し分けは利便性であって権限ではない（画面設計書 1.1 の原則 2）。
   *   ここで確かめているのは「人事にだけ出る」ことであって、
   *   一般社員が API を叩けないことはサーバ側のテストが確かめている。
   */
  test('IT-SCN-35 人事にだけ締め・就業規則・カレンダーのメニューが出る',
    async ({ page }) => {
      await signIn(page, EMPLOYEE);
      await expect(page.getByRole('button', { name: '締め' })).toHaveCount(0);
      await expect(page.getByRole('button', { name: '就業規則' })).toHaveCount(0);
      await expect(page.getByRole('button', { name: 'カレンダー' })).toHaveCount(0);

      await page.context().clearCookies();
      await signIn(page, HR);
      await expect(page.getByRole('button', { name: '締め' })).toBeVisible();
      await expect(page.getByRole('button', { name: '就業規則' })).toBeVisible();
      await expect(page.getByRole('button', { name: 'カレンダー' })).toBeVisible();
    });

  /**
   * ★ **未提出の社員が並ぶことが、この画面の存在理由である。**
   *   承認待ちの一覧（提出済みの行）からは原理的に出せない。
   *   行は提出時に初めて作られるので、行を軸にすると 1 人も出ない（落とし穴 120）。
   */
  test('IT-SCN-36 締め画面に未提出の社員が理由つきで並ぶ', async ({ page }) => {
    await signIn(page, HR);
    await page.getByRole('button', { name: '締め' }).click();

    await expect(page.getByRole('heading', { name: /の締め$/ })).toBeVisible();
    // seed の 3 名は誰も提出していない
    await expect(page.getByText('提出されていません').first()).toBeVisible();
    // ★ 締められる社員がいないので、押せるボタンが出ない
    await expect(page.getByRole('button', { name: /まとめて締める$/ })).toBeDisabled();
    await expect(
      page.getByText('いま締められる社員はいません')).toBeVisible();
  });

  /**
   * ★ 「編集」ではなく「改定」であることを画面で確かめる。
   *   改定は版を足す操作であって、既存の版を書き換える操作ではない。
   */
  test('IT-SCN-37 就業規則の系列を開くと版の履歴が出て、改定ボタンが出る',
    async ({ page }) => {
      await signIn(page, HR);
      await page.getByRole('button', { name: '就業規則' }).click();

      await expect(page.getByRole('cell', { name: '標準勤務' })).toBeVisible();
      // ★ 「編集」ボタンは置かない
      await expect(page.getByRole('button', { name: '編集' })).toHaveCount(0);

      await page.getByRole('button', { name: '開く' }).first().click();

      // 版の履歴。seed の版は 2026-04-01 から上限なし（現行）
      await expect(page.getByText('2026-04-01 〜 （現行）')).toBeVisible();
      // ★ 所定が読めていること。項目名を取り違えると NaN や undefined になる
      await expect(page.getByText('09:00–18:00（休憩 60 分・実働 8:00）')).toBeVisible();
      await expect(page.getByRole('button', { name: '改定する' })).toBeVisible();
    });

  /**
   * ★ 登録した結果を**読み直して**表示していることを確かめる。
   *   応答の版を手で書いた定数にすると、その値で改定して必ず 409 になる
   *   （落とし穴 158）。
   */
  test('IT-SCN-38 就業規則を登録すると一覧に増え、版の履歴が読める',
    async ({ page }) => {
      await signIn(page, HR);
      await page.getByRole('button', { name: '就業規則' }).click();
      await page.getByRole('button', { name: '新しい就業規則を登録する' }).click();

      await page.getByLabel('名称').fill('シナリオ用規則');
      await page.getByLabel('適用開始日').fill('2026-04-01');
      await page.getByLabel('労働時間制度').selectOption('FLEX');
      await page.getByRole('button', { name: '登録する', exact: true }).click();

      await expect(page.getByText('就業規則を登録しました。')).toBeVisible();
      await expect(page.getByRole('cell', { name: 'シナリオ用規則' })).toBeVisible();
      // ★ フレックスの項目が読めていること
      await expect(page.getByText(/フレキシブル 07:00–22:00／コア 11:00–15:00/))
        .toBeVisible();
    });

  /**
   * ★ 暦日区分を変えると、サーバが返す一覧のほうが変わることを確かめる。
   *   画面が自分の状態を書き換えるだけだと、読み直したときに元へ戻る。
   */
  test('IT-SCN-39 会社カレンダーの暦日区分を変えると一覧に反映される',
    async ({ page }) => {
      await signIn(page, HR);
      await page.getByRole('button', { name: 'カレンダー' }).click();
      // 対象月は画面上部のセレクタで決まる。seed が持つ 2026-04 に合わせる
      await page.getByLabel('対象月').fill('2026-04');

      const row = page.getByRole('row').filter({ hasText: '2026-04-29' });
      await expect(row).toContainText('所定労働日');

      await row.getByLabel('2026-04-29 の区分').selectOption('NON_LEGAL_HOLIDAY');

      await expect(page.getByRole('row').filter({ hasText: '2026-04-29' }))
        .toContainText('所定休日');
    });
});

async function signIn(page: Page, user: { number: string; password: string }) {
  await page.goto('/');
  await page.getByLabel('社員番号').fill(user.number);
  await page.getByLabel('パスワード').fill(user.password);
  await page.getByRole('button', { name: 'ログイン' }).click();
  await expect(page.getByRole('heading', { name: '勤怠管理システム' })).toBeVisible();
}
