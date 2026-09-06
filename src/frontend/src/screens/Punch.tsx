import { useCallback, useEffect, useState } from 'react';

import { ApiError, get, post } from '../api/client';
import { present, type Presentation } from '../api/problem';
import type { CurrentAttendance, PunchType, SignedIn } from '../api/types';
import { shortDateOf, timeOf } from '../api/wallClock';
import { ProblemBanner } from './ProblemBanner';

/**
 * SC-02 打刻（ホーム）。
 *
 * ★ ボタンの出し分けは `availableActions` だけで決める（画面設計書 1.1 の原則 1）。
 *   `status === 'WORKING'` のような分岐を書くと、状態機械が画面にも生まれ、
 *   BR-02 を直したときに 2 か所を保守することになる。
 *
 * ★ 打刻時刻を画面から送らない。端末の時計は信用できない。
 *   サーバが `Clock` から決める（AR-09）。
 *
 * ★ 未退勤の日があっても打刻ボタンを消さない。
 *   別の日の不整合で、その日の労働の記録を止めてはいけない
 *   （CLAUDE.md 落とし穴 19・68）。
 */
const LABELS: Record<PunchType, string> = {
  CLOCK_IN: '出勤',
  BREAK_START: '休憩開始',
  BREAK_END: '休憩終了',
  CLOCK_OUT: '退勤',
};

export function Punch({ user }: { user: SignedIn }) {
  const [current, setCurrent] = useState<CurrentAttendance | null>(null);
  const [problem, setProblem] = useState<Presentation | null>(null);
  const [busy, setBusy] = useState(false);

  const reload = useCallback(async () => {
    try {
      setCurrent(await get<CurrentAttendance>(
        `/api/employees/${user.id}/attendances/current`));
    } catch (error) {
      setProblem(presentationOf(error));
    }
  }, [user.id]);

  useEffect(() => { void reload(); }, [reload]);

  async function punch(type: PunchType) {
    setProblem(null);
    // ★ 二度押しでの二重打刻を防ぐ。ただし本当の防御はサーバの状態遷移検査である
    setBusy(true);
    try {
      await post(`/api/employees/${user.id}/time-clocks`, { type });
      await reload();
    } catch (error) {
      setProblem(presentationOf(error));
      await reload();
    } finally {
      setBusy(false);
    }
  }

  // ★ 読み込みに失敗したまま「読み込み中…」を出し続けない。
  //   利用者には永久に回り続ける画面としか見えず、
  //   何が起きたのか（401 なのか 404 なのか）を確かめる手段が無い。
  if (current === null) {
    return (
      <main>
        <ProblemBanner presentation={problem} />
        {problem === null
          ? <section><p>読み込み中…</p></section>
          : (
            <section>
              <button onClick={() => { setProblem(null); void reload(); }}>やり直す</button>
            </section>
          )}
      </main>
    );
  }

  return (
    <main>
      <ProblemBanner presentation={problem} />

      {current.unclosedWorkDates.length > 0 && (
        <div className="warning" role="status">
          <strong>退勤の打刻がありません:</strong>{' '}
          {current.unclosedWorkDates.map(shortDateOf).join('・')}
          <div className="muted">
            訂正申請で直してください。<strong>今日の打刻は続けられます。</strong>
          </div>
        </div>
      )}

      <section>
        <h2>打刻</h2>
        <p className="muted">
          勤務日 {current.workDate}
          {current.status !== null && `（${statusLabel(current.status)}）`}
        </p>
        <div>
          {/* ★ ここが原則 1 の実体。サーバが返した配列をそのまま並べる */}
          {current.availableActions.map((action) => (
            <button key={action} className="action" disabled={busy}
                    onClick={() => void punch(action)}>
              {LABELS[action]}
            </button>
          ))}
          {current.availableActions.length === 0 && (
            <p className="muted">本日の勤務は終了しています。</p>
          )}
        </div>
      </section>

      <section>
        <h2>今日の打刻</h2>
        {current.punches.length === 0
          ? <p className="muted">まだ打刻がありません。</p>
          : (
            <table>
              <thead><tr><th>種別</th><th>時刻</th></tr></thead>
              <tbody>
                {current.punches.map((punch) => (
                  <tr key={punch.id}>
                    <td>{LABELS[punch.type]}</td>
                    {/* ★ サーバの値をそのまま出す。`new Date()` を使わない */}
                    <td>{timeOf(punch.occurredAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
      </section>
    </main>
  );
}

function statusLabel(status: NonNullable<CurrentAttendance['status']>): string {
  switch (status) {
    case 'NOT_STARTED': return '未出勤';
    case 'WORKING': return '勤務中';
    case 'ON_BREAK': return '休憩中';
    case 'FINISHED': return '退勤済';
  }
}

export function presentationOf(error: unknown): Presentation {
  return error instanceof ApiError
    ? present(error.problem)
    : { kind: 'unknown', message: '通信に失敗しました' };
}
