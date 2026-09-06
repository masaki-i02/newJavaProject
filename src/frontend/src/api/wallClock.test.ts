import { describe, expect, it } from 'vitest';

import {
  asDate,
  asDateTime,
  closedRangeLabel,
  monthRangeOf,
  previousDayOf,
  shortDateOf,
  timeOf,
} from './wallClock';

/**
 * 壁掛け時計時刻（UT-FE-01〜06）。
 *
 * サーバは深夜帯を 22:00–05:00 として扱う。
 * 端末のタイムゾーンで解釈すると、その境界が目で確かめられなくなる
 * （CLAUDE.md 落とし穴 1 が画面に現れた形）。
 */
describe('壁掛け時計時刻', () => {
  /**
   * ★ この 1 件が、branded type を置いた理由そのものである。
   *   `new Date('2026-04-06T22:00:00')` は端末のタイムゾーンで解釈されるので、
   *   UTC の端末では 07:00 として表示される。
   */
  it('UT-FE-01 深夜帯の時刻を、端末のタイムゾーンに関係なくそのまま切り出す', () => {
    const night = asDateTime('2026-04-06T22:00:00');

    expect(timeOf(night)).toBe('22:00');
    // 参考。`Date` を経由すると端末次第で値が変わる
    expect(new Date(night).getHours()).not.toBe(Number.NaN);
  });

  it('UT-FE-02 形式が違う値は受け取らない', () => {
    expect(() => asDateTime('2026-04-06 22:00:00')).toThrow('壁掛け時計時刻の形式');
    expect(() => asDateTime('2026-04-06T22:00:00Z')).toThrow('壁掛け時計時刻の形式');
    expect(() => asDate('2026-4-6')).toThrow('日付の形式');
  });

  /**
   * ★ 半開区間の上限をそのまま見せない。
   *   `2026-05-01` を「4 月の終わり」として出すと必ず取り違える
   *   （CLAUDE.md 落とし穴 10・112）。
   */
  it('UT-FE-03 半開区間の上限を、閉区間の最終日として表示する', () => {
    expect(closedRangeLabel(asDate('2026-04-01'), asDate('2026-05-01')))
      .toBe('2026-04-01 〜 2026-04-30');
  });

  /** 月をまたぐ・年をまたぐ・うるう年。**境界の内側だけを選ばない。** */
  it('UT-FE-04 前日の計算が月・年・うるう年の境界をまたぐ', () => {
    expect(previousDayOf(asDate('2026-05-01'))).toBe('2026-04-30');
    expect(previousDayOf(asDate('2026-01-01'))).toBe('2025-12-31');
    expect(previousDayOf(asDate('2024-03-01')))
      .toBe('2024-02-29'); // うるう年
    expect(previousDayOf(asDate('2026-03-01')))
      .toBe('2026-02-28');
  });

  it('UT-FE-05 月の半開区間が 12 月をまたぐ', () => {
    expect(monthRangeOf('2026-04' as never))
      .toEqual({ from: '2026-04-01', toExclusive: '2026-05-01' });
    expect(monthRangeOf('2026-12' as never))
      .toEqual({ from: '2026-12-01', toExclusive: '2027-01-01' });
  });

  it('UT-FE-06 表示用の日付は 0 埋めしない', () => {
    expect(shortDateOf(asDate('2026-04-06'))).toBe('4/6');
    expect(shortDateOf(asDate('2026-12-25'))).toBe('12/25');
  });
});
