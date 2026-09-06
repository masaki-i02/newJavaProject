import { useCallback, useEffect, useState } from 'react';

import { get, post } from '../api/client';
import type { Presentation } from '../api/problem';
import type { MonthlyAttendance } from '../api/types';
import type { YearMonth } from '../api/wallClock';
import { ProblemBanner } from './ProblemBanner';
import { stateLabel } from './MyMonth';
import { presentationOf } from './Punch';

/**
 * SC-05 承認待ち一覧 / SC-06 部下の月次勤怠。
 *
 * ★ 一覧は「その利用者が承認できるものだけ」が返る。
 *   画面は並べるだけで、BR-11 の承認者導出を行わない（画面設計書 4.3）。
 *
 * ★ 詳細を開いたときにだけ `canApprove` と履歴を引く。
 *   一覧の行にそれらは載っていない（行ごとに引くと社員数ぶん重複する）。
 */
export function Approvals({ month }: { month: YearMonth }) {
  const [pending, setPending] = useState<readonly MonthlyAttendance[]>([]);
  const [selected, setSelected] = useState<MonthlyAttendance | null>(null);
  const [reason, setReason] = useState('');
  const [problem, setProblem] = useState<Presentation | null>(null);
  const [busy, setBusy] = useState(false);

  const reload = useCallback(async () => {
    try {
      setPending(await get<readonly MonthlyAttendance[]>(
        `/api/monthly-attendances/pending-approval?month=${month}`));
    } catch (error) {
      setProblem(presentationOf(error));
    }
  }, [month]);

  useEffect(() => { void reload(); }, [reload]);

  async function open(row: MonthlyAttendance) {
    setProblem(null);
    setReason('');
    try {
      setSelected(await get<MonthlyAttendance>(
        `/api/employees/${row.employeeId}/monthly-attendances/${month}`));
    } catch (error) {
      setProblem(presentationOf(error));
    }
  }

  async function decide(path: 'approval' | 'rejection') {
    if (selected === null) return;
    setProblem(null);
    setBusy(true);
    try {
      const body = path === 'rejection'
        ? { version: selected.version, reason }
        : { version: selected.version };
      // ★ 応答は遷移したあとの版と可否を返す。返さないと画面は必ず 1 回 409 を踏む
      setSelected(await post<MonthlyAttendance>(
        `/api/employees/${selected.employeeId}/monthly-attendances/${month}/${path}`,
        body));
      await reload();
    } catch (error) {
      setProblem(presentationOf(error));
      // 楽観ロックで弾かれたときは、読み直さないと次も必ず失敗する
      await open(selected);
    } finally {
      setBusy(false);
    }
  }

  return (
    <main>
      <ProblemBanner presentation={problem} />

      <section>
        <h2>{month} の承認待ち</h2>
        {pending.length === 0
          ? <p className="muted">承認待ちはありません。</p>
          : (
            <table>
              <thead>
                <tr><th>社員</th><th>状態</th><th>版</th><th /></tr>
              </thead>
              <tbody>
                {pending.map((row) => (
                  <tr key={row.employeeId}>
                    {/* ★ 氏名は返らない。employee が所有する概念なので混ぜない */}
                    <td>{row.employeeId}</td>
                    <td>{stateLabel(row.status)}</td>
                    <td className="num">{row.version}</td>
                    <td>
                      <button className="action secondary"
                              onClick={() => void open(row)}>
                        開く
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
      </section>

      {selected !== null && (
        <section>
          <h2>{selected.employeeId} の {month}</h2>
          <p className="muted">
            状態：{stateLabel(selected.status)} ／ 版：{selected.version}
            {selected.approver !== undefined
              && ` ／ 承認者：${approverLabel(selected)}`}
          </p>

          <div>
            {/* ★ ここが原則 1。「提出済なら承認できる」と書かない。
                自己承認の禁止も承認者かどうかも、サーバが判断して返す */}
            <button className="action" disabled={busy || selected.canApprove !== true}
                    onClick={() => void decide('approval')}>
              承認する
            </button>
            <button className="action secondary"
                    disabled={busy || selected.canApprove !== true
                              || reason.trim().length === 0}
                    onClick={() => void decide('rejection')}>
              差し戻す
            </button>
          </div>
          <p>
            <label htmlFor="reason">差戻しの理由（必須）</label><br />
            <input id="reason" value={reason} size={60}
                   onChange={(e) => setReason(e.target.value)} />
          </p>
          {selected.canApprove === false && (
            <p className="muted">この月をあなたが承認することはできません。</p>
          )}

          {selected.history !== undefined && selected.history.length > 0 && (
            <>
              <h2>履歴</h2>
              <table>
                <thead>
                  <tr><th>遷移</th><th>前</th><th>後</th><th>理由</th><th>日時</th></tr>
                </thead>
                <tbody>
                  {selected.history.map((entry) => (
                    <tr key={`${entry.occurredAt}-${entry.eventKind}`}>
                      <td>{entry.eventKind}</td>
                      <td>{stateLabel(entry.fromStatus)}</td>
                      <td>{stateLabel(entry.toStatus)}</td>
                      <td>{entry.comment ?? ''}</td>
                      <td>{entry.occurredAt}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
        </section>
      )}
    </main>
  );
}

function approverLabel(attendance: MonthlyAttendance): string {
  switch (attendance.approver?.kind) {
    case 'INDIVIDUAL': return attendance.approver.employeeId ?? '不明';
    case 'HUMAN_RESOURCES': return '人事';
    case 'NONE': return '導出できません';
    default: return '不明';
  }
}
