import { describe, expect, it } from 'vitest';

import { KNOWN_PROBLEMS, present, type Problem } from './problem';

/**
 * Problem Details の見せ方（UT-FE-07〜12）。
 *
 * ★ `status` で分岐しない。409 には「他の利用者が先に更新した」と
 *   「対象月がまだ終わっていない」が両方あり、案内がまったく違う。
 */
describe('Problem Details', () => {
  const problem = (type: string, extra: Partial<Problem> = {}): Problem => ({
    type,
    title: 'エラー',
    status: 409,
    ...extra,
  });

  /**
   * ★ 楽観ロックだけは「読み直してもう一度」と案内する。
   *   他の 409 と同じバナーにすると、利用者は何をすればよいか分からない。
   */
  it('UT-FE-07 楽観ロックの失敗だけは読み直しを促す', () => {
    expect(present(problem('urn:kintai:error:optimistic-lock-failure')))
      .toEqual({ kind: 'reload', message: expect.stringContaining('読み直して') });
  });

  it('UT-FE-08 同じ 409 でも、締め済みはバナーで理由を出す', () => {
    expect(present(problem('urn:kintai:error:month-already-closed', {
      detail: '2026 年 4 月は締め済みです',
    }))).toEqual({
      kind: 'banner',
      title: 'エラー',
      detail: '2026 年 4 月は締め済みです',
    });
  });

  it('UT-FE-09 入力の不備は項目に紐づける', () => {
    const presentation = present(problem('urn:kintai:error:validation-failed', {
      status: 400,
      errors: [{ field: 'reason', message: '理由は必須です' }],
    }));

    expect(presentation).toEqual({
      kind: 'field',
      errors: [{ field: 'reason', message: '理由は必須です' }],
    });
  });

  it('UT-FE-10 未認証はログイン画面へ戻す', () => {
    expect(present(problem('urn:kintai:error:authentication-failed', { status: 401 })))
      .toEqual({ kind: 'signIn' });
  });

  /**
   * ★ 未知の `type` の `detail` を捨てる。
   *   内部の構造（制約名・テーブル名）が載っている可能性がある。
   *   サーバは実装の不備の応答にメッセージを載せない方針だが、
   *   画面の側でも同じ扱いにしておく。
   */
  it('UT-FE-11 未知の type は詳細を出さない', () => {
    const presentation = present(problem('urn:kintai:error:brand-new-error', {
      status: 500,
      detail: 'null value in column "employee_id" violates not-null constraint',
    }));

    expect(presentation.kind).toBe('unknown');
    expect(JSON.stringify(presentation)).not.toContain('employee_id');
  });

  /**
   * ★ 既知の `type` をすべて処理していることを確かめる。
   *   網羅性検査はコンパイル時に効くが、`KNOWN_PROBLEMS` に足して
   *   `switch` にも足したのに **間違った分岐へ入れた** 場合は落ちない。
   */
  it('UT-FE-12 既知の type はすべて unknown 以外に落ちる', () => {
    for (const type of KNOWN_PROBLEMS) {
      expect(present(problem(type)).kind, type).not.toBe('unknown');
    }
  });
});
