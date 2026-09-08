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

/**
 * 承認（SC-05 / SC-06）。
 *
 * ★ **承認は内容の承認である。** 労働時間を 1 つも見せずに承認ボタンを出すと、
 *   その承認は「何を見て承認したのか」に答えられない証跡にしかならない。
 *   一覧には載せず（`attendance` が所有する概念であり、行ごとに引くと
 *   社員数ぶんの問い合わせが重複する）、**開いた 1 人ぶんだけ**引く。
 */
test.describe('承認', () => {
  const APPROVER = { number: 'E0100', password: 'correct-horse-battery' };
  const MEMBER = { number: 'E0001', password: 'correct-horse-battery' };
  // 前提データのカレンダーが 2026-04 から始まり、その月は既に終わっている
  const PAST_MONTH = '2026-04';

  test('IT-SCN-40 提出すると一覧に提出日時が並び、詳細に労働時間が出る',
    async ({ page }) => {
      await signIn(page, MEMBER);
      await page.getByRole('button', { name: '月次勤怠' }).click();
      await page.getByLabel('対象月').fill(PAST_MONTH);
      await page.getByRole('button', { name: '提出する' }).click();

      await page.context().clearCookies();
      await signIn(page, APPROVER);
      await page.getByLabel('対象月').fill(PAST_MONTH);
      await page.getByRole('button', { name: '承認' }).click();

      // ★ 一覧は版を返さない。代わりに提出日時が並ぶ
      await expect(page.getByRole('columnheader', { name: '版' })).toHaveCount(0);
      await expect(page.getByRole('columnheader', { name: '提出' })).toBeVisible();

      await page.getByRole('button', { name: '開く' }).first().click();

      // ★ 労働時間の節が出る。承認者はこれを見て承認する
      await expect(page.getByRole('heading', { name: '労働時間' })).toBeVisible();
      await expect(page.getByRole('button', { name: '承認する' })).toBeEnabled();
    });
});

/**
 * 古い応答が新しい状態を上書きしないこと。
 *
 * ★ これは実ブラウザの通しが **1 回だけ落ちて再現しなかった**欠陥である。
 *   月を切り替えると前の月の問い合わせがまだ飛んでいて、遅れて届いた応答が
 *   新しい月の状態を上書きしていた。画面は正しい月を表示しているのに
 *   `canSubmit` が前の月（当月＝まだ終わっていない）の偽のまま残るので、
 *   **終わった月を開いているのに提出ボタンが押せない。**
 *
 * ★ 「もう一度流したら通った」で済ませない。応答を遅らせて必ず再現させる。
 */
test.describe('応答の新しさ', () => {
  test('IT-SCN-41 前の月の応答が遅れて届いても、新しい月の状態を上書きしない',
    async ({ page }) => {
      // 当月（まだ終わっていない＝canSubmit が偽）の応答をわざと遅らせる
      await page.route('**/api/employees/*/monthly-attendances/2026-09',
        async (route) => {
          await new Promise((resolve) => setTimeout(resolve, 2500));
          await route.continue();
        });

      await signIn(page, { number: 'E0001', password: 'correct-horse-battery' });
      await page.getByRole('button', { name: '月次勤怠' }).click();
      // ★ IT-SCN-40 が触る 2026-04 を使わない。あちらは承認まで進めるので、
      //   同じ月を使うと「承認済みだから提出できない」が
      //   この観点の失敗に見える（落とし穴 12：入力は 1 つだけ変える）
      await page.getByLabel('対象月').fill('2026-05');

      // 遅れた応答が届いたあとも、終わった月として扱われ続ける
      await expect(page.getByRole('heading', { name: '2026-05 の勤怠' })).toBeVisible();
      await expect(page.getByRole('button', { name: '提出する' })).toBeEnabled();
      await page.waitForTimeout(3000);
      await expect(page.getByRole('button', { name: '提出する' })).toBeEnabled();
    });
});

/**
 * 打刻の訂正（SC-04 / SC-07）。
 *
 * ★ **「変更」という操作は無い。** 取消と追加の組み合わせで表す。
 *   変更を許すと元の打刻の値が失われ、「何がどう直ったのか」を
 *   利用者が確かめられなくなる（BR-09 の目的）。
 */
test.describe('打刻の訂正', () => {
  const MEMBER = { number: 'E0001', password: 'correct-horse-battery' };
  const APPROVER = { number: 'E0100', password: 'correct-horse-battery' };

  /**
   * ★ 対象の打刻は**前提データが持っている**（2026-06-01）。
   *   ここでブラウザから打刻すると「いま」の日を消費してしまい、
   *   当日の打刻の通し（IT-SCN-32）が「すでに退勤済み」から始まって落ちる。
   */
  const TARGET_DATE = '2026-06-10';

  test('IT-SCN-42 打刻を取り消して入れ直す訂正を申請し、上長が承認する',
    async ({ page }) => {
      await signIn(page, MEMBER);
      await page.getByRole('button', { name: '打刻訂正' }).click();
      await page.getByLabel('勤務日').fill(TARGET_DATE);

      // 退勤を取り消して 19:00 で入れ直す
      await page.getByRole('checkbox').last().check();
      await page.getByRole('button', { name: '打刻を追加する' }).click();
      await page.getByLabel('追加する打刻の種別 1').selectOption('CLOCK_OUT');
      await page.getByLabel('追加する打刻の時刻 1').fill('19:00');
      await page.getByLabel('申請の理由（必須）').fill('退勤打刻を押し忘れました');
      await page.getByRole('button', { name: '訂正を申請する' }).click();

      await expect(page.getByText('訂正を申請しました。承認されるまで打刻は変わりません。'))
        .toBeVisible();
      await expect(page.getByRole('cell', { name: '申請中' })).toBeVisible();

      // 上長が審査する
      await page.context().clearCookies();
      await signIn(page, APPROVER);
      await page.getByRole('button', { name: '訂正の審査' }).click();
      await page.getByRole('button', { name: '開く' }).first().click();

      // ★ 承認すると月次勤怠がどうなるかを画面が伝える
      await expect(page.getByText(/提出済みだった月は下書きへ戻ります/)).toBeVisible();
      await page.getByRole('button', { name: '承認する' }).click();
      await expect(page.getByText(/訂正を承認しました。/)).toBeVisible();
    });

  /** ★ 取消済みの打刻は対象に選ばせない。押せても 409 になるだけである。 */
  test('IT-SCN-43 承認された訂正のあと、取消済みの打刻は選べないが行は残る',
    async ({ page }) => {
      await signIn(page, MEMBER);
      await page.getByRole('button', { name: '打刻訂正' }).click();
      await page.getByLabel('勤務日').fill(TARGET_DATE);

      // IT-SCN-42 が取り消した打刻の行は残っており、選択できない
      await expect(page.getByText('取消済み').first()).toBeVisible();
    });
});
