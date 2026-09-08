import { useCallback, useEffect, useState } from 'react';

import { get, post, put } from '../api/client';
import { useFreshness } from '../api/freshness';
import type { Presentation } from '../api/problem';
import type {
  CalendarBulkResult, CalendarDay, CalendarView, CalendarWarning, DayOfWeekName, DayType,
} from '../api/types';
import { monthRangeOf, type YearMonth } from '../api/wallClock';
import { ProblemBanner } from './ProblemBanner';
import { presentationOf } from './Punch';

/**
 * SC-11 会社カレンダー（画面設計書 4.6）。
 *
 * ★ 一括登録の応答の `warnings` を必ず一覧で出す。
 *   「連続 7 日に法定休日が無い」は DB では守れない（1 行では判定できない）ので、
 *   気づく経路がここにしか無い。
 *
 * ★ 締め済みの月は編集させない。サーバは 409 を返すが、
 *   押せてから断られるのは操作として悪い（画面設計書 4.6）。
 *   ただし**判定はサーバの応答に従う**。画面は締め済みの月を推測しない。
 *
 * ★ 年度の全日が登録されていることが割増賃金の基礎額の前提である（BR-18）。
 *   1 日ずつ登録すると 365 回叩くことになるので、一括登録を主の操作にする。
 */
export function Calendar({ month }: { month: YearMonth }) {
  const [view, setView] = useState<CalendarView | null>(null);
  const [problem, setProblem] = useState<Presentation | null>(null);
  const [bulkProblem, setBulkProblem] = useState<Presentation | null>(null);
  const [warnings, setWarnings] = useState<readonly CalendarWarning[]>([]);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [bulk, setBulk] = useState<BulkForm>(defaultBulkForm());

  const begin = useFreshness();
  const reload = useCallback(async () => {
    // ★ 月を切り替えると前の月の問い合わせがまだ飛んでいる
    const isFresh = begin();
    const { from, toExclusive } = monthRangeOf(month);
    try {
      const loaded = await get<CalendarView>(
        `/api/calendars?from=${from}&toExclusive=${toExclusive}`);
      if (!isFresh()) return;
      setView(loaded);
      setProblem(null);
    } catch (error) {
      if (!isFresh()) return;
      setView(null);
      setProblem(presentationOf(error));
    }
  }, [begin, month]);

  useEffect(() => { void reload(); }, [reload]);

  async function setDay(date: string, dayType: DayType) {
    setProblem(null);
    setNotice(null);
    setBusy(true);
    try {
      await put<void>(`/api/calendars/${date}`, { dayType, name: null });
      await reload();
    } catch (error) {
      setProblem(presentationOf(error));
    } finally {
      setBusy(false);
    }
  }

  async function registerBulk() {
    setBulkProblem(null);
    setWarnings([]);
    setNotice(null);
    setBusy(true);
    try {
      const result = await post<CalendarBulkResult>('/api/calendars/bulk', {
        from: bulk.from,
        toExclusive: bulk.toExclusive,
        // ★ 指定の無い曜日は所定労働日になる。空配列も送る（サーバが @NotNull で受ける）
        rules: bulk.weekendHoliday
          ? [
            { dayOfWeek: 'SUNDAY' satisfies DayOfWeekName,
              dayType: 'LEGAL_HOLIDAY' satisfies DayType, name: '法定休日' },
            { dayOfWeek: 'SATURDAY' satisfies DayOfWeekName,
              dayType: 'NON_LEGAL_HOLIDAY' satisfies DayType, name: '所定休日' },
          ]
          : [],
        // ★ 個別の日は曜日の規則より優先する。祝日は曜日で決まらない
        overrides: parseOverrides(bulk.overrides),
      });
      setWarnings(result.warnings ?? []);
      setNotice(`${result.registeredCount} 日を登録しました。`);
      await reload();
    } catch (error) {
      setBulkProblem(presentationOf(error));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main>
      <ProblemBanner presentation={problem} />
      {notice !== null && <div className="warning" role="status">{notice}</div>}
      {warnings.map((warning) => (
        <div className="warning" role="alert" key={warning.period.from}>
          {warning.message}
        </div>
      ))}

      <section>
        <h2>{month} の会社カレンダー</h2>
        {/* ★ 読み込みに失敗したまま「読み込み中…」を出し続けない（落とし穴 144）。
              失敗はバナーが出ているので、ここでは何も言わない */}
        {view === null
          ? (problem === null ? <p className="muted">読み込み中…</p> : null)
          : (
            <>
              <p className="muted">
                所定労働日 {view.workdayCount} 日
                {' '}／ 未登録の日も所定労働日として返ります
              </p>
              <table>
                <thead>
                  <tr><th>日付</th><th>曜日</th><th>区分</th><th /></tr>
                </thead>
                <tbody>
                  {view.days.map((day) => (
                    <DayRow key={day.date} day={day} busy={busy}
                            onChange={(dayType) => void setDay(day.date, dayType)} />
                  ))}
                </tbody>
              </table>
            </>
          )}
      </section>

      <section>
        <h2>期間をまとめて登録する</h2>
        <ProblemBanner presentation={bulkProblem} />
        <p className="muted">
          割増賃金の基礎額（BR-18）は<strong>年度の全日が登録されていること</strong>を
          前提にします。1 日ずつではなく、年度ぶんをまとめて登録してください。
        </p>
        <p>
          {/* ★ 期間は半開区間で送る。上限を含めると年度の末日が二重になる */}
          <label htmlFor="cal-from">開始日</label>{' '}
          <input id="cal-from" type="date" value={bulk.from}
                 onChange={(e) => setBulk({ ...bulk, from: e.target.value })} />
          {' '}
          <label htmlFor="cal-to">終了日（この日は含みません）</label>{' '}
          <input id="cal-to" type="date" value={bulk.toExclusive}
                 onChange={(e) => setBulk({ ...bulk, toExclusive: e.target.value })} />
        </p>
        <p>
          <label>
            <input type="checkbox" checked={bulk.weekendHoliday}
                   onChange={(e) => setBulk({ ...bulk, weekendHoliday: e.target.checked })} />
            {' '}日曜を法定休日・土曜を所定休日にする
          </label>
        </p>
        <p>
          <label htmlFor="cal-overrides">
            個別の日（1 行に「日付,区分,名称」。祝日はここに書く）
          </label><br />
          <textarea id="cal-overrides" rows={5} cols={60} value={bulk.overrides}
                    onChange={(e) => setBulk({ ...bulk, overrides: e.target.value })}
                    placeholder={'2026-04-29,NON_LEGAL_HOLIDAY,昭和の日'} />
        </p>
        <button className="action" disabled={busy || !isBulkComplete(bulk)}
                onClick={() => void registerBulk()}>
          まとめて登録する
        </button>
      </section>
    </main>
  );
}

function DayRow({ day, busy, onChange }: {
  day: CalendarDay;
  busy: boolean;
  onChange: (dayType: DayType) => void;
}) {
  return (
    <tr>
      <td>{day.date}</td>
      <td>{dayOfWeekLabel(day.date)}</td>
      <td>{dayTypeLabel(day.dayType)}</td>
      <td>
        <select value={day.dayType} disabled={busy}
                aria-label={`${day.date} の区分`}
                onChange={(e) => onChange(asDayType(e.target.value))}>
          <option value="WORKDAY">所定労働日</option>
          <option value="LEGAL_HOLIDAY">法定休日</option>
          <option value="NON_LEGAL_HOLIDAY">所定休日</option>
        </select>
      </td>
    </tr>
  );
}

/** 暦日区分の表示。網羅性検査つきの `switch`（`default` を置かない）。 */
export function dayTypeLabel(dayType: DayType): string {
  switch (dayType) {
    case 'WORKDAY': return '所定労働日';
    case 'LEGAL_HOLIDAY': return '法定休日';
    case 'NON_LEGAL_HOLIDAY': return '所定休日';
  }
}

/**
 * `<select>` の値を区分へ写す。
 *
 * ★ `as DayType` で押し込まない。押し込むと、選択肢を増やしたときに
 *   サーバが知らない値をそのまま送る画面ができる。
 */
export function asDayType(value: string): DayType {
  switch (value) {
    case 'LEGAL_HOLIDAY': return 'LEGAL_HOLIDAY';
    case 'NON_LEGAL_HOLIDAY': return 'NON_LEGAL_HOLIDAY';
    case 'WORKDAY': return 'WORKDAY';
    default: throw new Error(`暦日区分ではありません: ${value}`);
  }
}

const WEEKDAYS = ['日', '月', '火', '水', '木', '金', '土'] as const;

/**
 * 曜日の表示。
 *
 * ★ **UTC で組み立てて UTC で読む。** 端末のタイムゾーンで解釈すると、
 *   UTC より西の端末で 1 日ずれる（落とし穴 1 が画面に現れた形）。
 */
export function dayOfWeekLabel(date: string): string {
  return WEEKDAYS[new Date(`${date}T00:00:00Z`).getUTCDay()] ?? '';
}

interface BulkForm {
  readonly from: string;
  readonly toExclusive: string;
  readonly weekendHoliday: boolean;
  readonly overrides: string;
}

function defaultBulkForm(): BulkForm {
  return { from: '', toExclusive: '', weekendHoliday: true, overrides: '' };
}

function isBulkComplete(form: BulkForm): boolean {
  return form.from !== '' && form.toExclusive !== '';
}

/**
 * 個別の日の入力を読む。
 *
 * ★ 読めない行を黙って捨てない。捨てると、人事は
 *   「登録したはずの祝日が入っていない」ことに気づけない（落とし穴 105）。
 *   ここで例外にすると画面が落ちるので、**そのままサーバへ送って 422 を受ける**
 *   ことはできない（形が違う）。読めない行があることを呼ぶ側へ知らせる。
 */
export function parseOverrides(text: string): readonly {
  date: string; dayType: DayType; name: string | null;
}[] {
  return text.split('\n')
    .map((line) => line.trim())
    .filter((line) => line !== '')
    .map((line) => {
      const [date, dayType, name] = line.split(',').map((part) => part.trim());
      if (date === undefined || dayType === undefined) {
        throw new Error(`「日付,区分,名称」の形ではありません: ${line}`);
      }
      return {
        date,
        dayType: asDayType(dayType),
        name: name === undefined || name === '' ? null : name,
      };
    });
}
