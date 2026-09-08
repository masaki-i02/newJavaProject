import { describe, expect, it } from 'vitest';

import { SPECIAL_PROBLEMS, present, type Problem } from './problem';

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
   * ★ 実装の不備と DB の制約違反だけは `detail` を捨てる。
   *   制約名やテーブル名という**内部の構造**が載りうる唯一の経路である。
   *   サーバは載せない方針だが、画面の側でも落としておく。
   */
  it('UT-FE-11 実装の不備と制約違反は詳細を出さない', () => {
    for (const type of ['urn:kintai:error:internal-error',
      'urn:kintai:error:constraint-violation']) {
      const presentation = present(problem(type, {
        status: 500,
        detail: 'null value in column "employee_id" violates not-null constraint',
      }));

      expect(presentation.kind, type).toBe('unknown');
      expect(JSON.stringify(presentation), type).not.toContain('employee_id');
    }
  });

  /**
   * ★ 見せ方が既定と違う `type` をすべて処理していることを確かめる。
   *   網羅性検査はコンパイル時に効くが、`SPECIAL_PROBLEMS` に足して
   *   `switch` にも足したのに **間違った分岐へ入れた** 場合は落ちない。
   */
  it('UT-FE-12 見せ方が既定と違う type はすべて意図した見せ方になる', () => {
    const expected: Record<string, string> = {
      'urn:kintai:error:validation-failed': 'field',
      'urn:kintai:error:authentication-failed': 'signIn',
      'urn:kintai:error:optimistic-lock-failure': 'reload',
      'urn:kintai:error:internal-error': 'unknown',
      'urn:kintai:error:constraint-violation': 'unknown',
    };
    for (const type of SPECIAL_PROBLEMS) {
      expect(present(problem(type)).kind, type).toBe(expected[type]);
    }
  });

  /**
   * ★ **一覧に無い業務エラーもバナーで理由を出す。**
   *   ここが「エラーが発生しました」に落ちると、人事がその場で直せる誤り
   *   （就業規則の指定・年間の所定が法定の総枠を超える）で理由が消える。
   *   サーバがエラーを足すたびに画面を直さないと伝わらない状態を作らない。
   */
  it('UT-FE-13 一覧に無い業務エラーもサーバの文言をそのまま出す', () => {
    expect(present(problem('urn:kintai:error:calendar-exceeds-statutory-year', {
      status: 422,
      title: '年間の所定労働時間が法定の総枠を超えます',
      detail: '2026 年度は 2,100 時間で、総枠 2,085 時間 42 分を超えます',
    }))).toEqual({
      kind: 'banner',
      title: '年間の所定労働時間が法定の総枠を超えます',
      detail: '2026 年度は 2,100 時間で、総枠 2,085 時間 42 分を超えます',
    });
  });

  /**
   * ★ 名前空間の外は出さない。
   *   CSRF の検証失敗（403）やプロキシの応答は、誰が書いた文言か分からない。
   */
  it('UT-FE-14 業務エラーの名前空間の外は文言を出さない', () => {
    const presentation = present(problem('about:blank', {
      status: 403,
      title: 'Forbidden',
      detail: '/actuator/env is denied by anyRequest().denyAll()',
    }));

    expect(presentation.kind).toBe('unknown');
    expect(JSON.stringify(presentation)).not.toContain('actuator');
  });
});
