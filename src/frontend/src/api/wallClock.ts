/**
 * 壁掛け時計時刻。
 *
 * サーバは `LocalDateTime` / `LocalDate` を **オフセットなしの文字列** で返す
 * （API 共通仕様 1.1）。ドメインが壁掛け時計時刻を扱い、
 * タイムゾーンの変換は `BusinessZone`（Asia/Tokyo）に集約しているためである
 * （アーキテクチャ設計書 6.3）。
 *
 * ★ `new Date()` に渡してはならない。
 *   `new Date('2026-04-06T22:00:00')` はブラウザのタイムゾーンで解釈されるので、
 *   端末が UTC なら 22:00 が 07:00 として表示される。
 *   深夜帯（22:00–05:00）の判定を目で確かめられなくなる
 *   （CLAUDE.md 落とし穴 1 が画面に現れた形）。
 *
 * そこで **branded type** にする。ただの `string` から代入できないので、
 * `new Date(wallClock)` を書こうとした時点で型エラーになる。
 */
declare const wallClockBrand: unique symbol;

/** `2026-04-06T09:00:00` の形。オフセットを持たない。 */
export type WallClockDateTime = string & { readonly [wallClockBrand]: 'dateTime' };

/** `2026-04-06` の形。 */
export type WallClockDate = string & { readonly [wallClockBrand]: 'date' };

/** `2026-04` の形。 */
export type YearMonth = string & { readonly [wallClockBrand]: 'yearMonth' };

const DATE_TIME = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;
const YEAR_MONTH = /^\d{4}-\d{2}$/;

/**
 * サーバの応答から取り出す。
 *
 * ★ 検証してから brand を付ける。付けるだけだと、
 *   型が「検証済み」と言っているのに検証していない状態になる。
 */
export function asDateTime(value: string): WallClockDateTime {
  if (!DATE_TIME.test(value)) {
    throw new Error(`壁掛け時計時刻の形式ではありません: ${value}`);
  }
  return value as WallClockDateTime;
}

export function asDate(value: string): WallClockDate {
  if (!DATE.test(value)) {
    throw new Error(`日付の形式ではありません: ${value}`);
  }
  return value as WallClockDate;
}

export function asYearMonth(value: string): YearMonth {
  if (!YEAR_MONTH.test(value)) {
    throw new Error(`年月の形式ではありません: ${value}`);
  }
  return value as YearMonth;
}

/**
 * 表示用の時刻（`09:00`）。
 *
 * ★ 文字列のまま切り出す。`Date` を経由しない。
 */
export function timeOf(value: WallClockDateTime): string {
  return value.slice(11, 16);
}

/** 表示用の日付（`4/6`）。 */
export function shortDateOf(value: WallClockDate): string {
  const [, month, day] = value.split('-');
  return `${Number(month)}/${Number(day)}`;
}

/**
 * 半開区間 `[from, toExclusive)` を閉区間の表示に直す。
 *
 * ★ 上限をそのまま表示しない。
 *   `2026-05-01` を「5 月の終わり」として見せると必ず取り違える
 *   （CLAUDE.md 落とし穴 10・112）。
 */
export function closedRangeLabel(from: WallClockDate,
                                 toExclusive: WallClockDate): string {
  return `${from} 〜 ${previousDayOf(toExclusive)}`;
}

/**
 * 前日。
 *
 * ★ ここだけは日付の演算が要る。`Date` を使うが、**UTC で組み立てて UTC で読む**
 *   ので、端末のタイムゾーンに依存しない。壁掛け時計の値をそのまま扱っている。
 */
export function previousDayOf(value: WallClockDate): WallClockDate {
  const utc = new Date(`${value}T00:00:00Z`);
  utc.setUTCDate(utc.getUTCDate() - 1);
  return utc.toISOString().slice(0, 10) as WallClockDate;
}

/** 月の半開区間。`2026-04` → `[2026-04-01, 2026-05-01)`。 */
export function monthRangeOf(month: YearMonth): {
  from: WallClockDate;
  toExclusive: WallClockDate;
} {
  const [year, monthOfYear] = month.split('-').map(Number) as [number, number];
  const next = monthOfYear === 12
    ? `${year + 1}-01-01`
    : `${year}-${String(monthOfYear + 1).padStart(2, '0')}-01`;
  return {
    from: `${month}-01` as WallClockDate,
    toExclusive: next as WallClockDate,
  };
}
