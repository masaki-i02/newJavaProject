import { describe, expect, it } from 'vitest';

import { createFreshness } from './freshness';

/**
 * 番号札（UT-FE-15）。
 *
 * ★ 実ブラウザの通しが 1 回だけ落ち、再現しなかった欠陥の再発防止である。
 *   古い月の応答が遅れて届くと、新しい月を表示したまま中身が前の月になり、
 *   `canSubmit` が偽のまま提出ボタンが押せなくなっていた。
 */
describe('応答の新しさ', () => {
  it('UT-FE-15 あとから始めた問い合わせだけが新しいと判定される', () => {
    const begin = createFreshness();

    const first = begin();
    expect(first(), '1 本だけなら新しい').toBe(true);

    const second = begin();
    expect(first(), '追い越されたら古い').toBe(false);
    expect(second(), '最後に始めたものは新しい').toBe(true);

    // ★ 3 本目を始めても、2 本目が「新しい」に戻ることはない
    begin();
    expect(second(), '追い越されたあとに戻らない').toBe(false);
  });

  /** ★ 画面ごとに独立している。1 つの札が別の画面の判定を狂わせない。 */
  it('UT-FE-16 別々に配った札は互いに影響しない', () => {
    const one = createFreshness();
    const other = createFreshness();

    const ticket = one();
    other();

    expect(ticket()).toBe(true);
  });
});
