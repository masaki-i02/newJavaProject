import { useCallback, useEffect, useState } from 'react';

import { get, post } from '../api/client';
import { useFreshness } from '../api/freshness';
import type { Presentation } from '../api/problem';
import type {
  AttendanceList, DailyAttendance, MonthlyAttendance, MonthlySettlement, PendingApproval,
} from '../api/types';
import { monthRangeOf, shortDateOf, type YearMonth } from '../api/wallClock';
import { ProblemBanner } from './ProblemBanner';
import { stateLabel } from './MyMonth';
import { hoursLabel } from './WorkRules';
import { presentationOf } from './Punch';

/**
 * SC-05 承認待ち一覧 / SC-06 部下の月次勤怠。
 *
 * ★ 一覧は**閲覧範囲**で絞られる（BR-11 の承認者導出ではない）。
 *   だから自分自身の提出済みの月も並び、その行は `canApprove` が偽になる。
 *   画面は並べるだけで、承認者かどうかの判定を行わない（画面設計書 4.3）。
 *
 * ★ 詳細を開いたときにだけ `canApprove` と履歴を引く。
 *   一覧の行にそれらは載っていない（行ごとに引くと社員数ぶん重複する）。
 */
export function Approvals({ month }: { month: YearMonth }) {
  const [pending, setPending] = useState<readonly PendingApproval[]>([]);
  const [selected, setSelected] = useState<MonthlyAttendance | null>(null);
  // ★ 承認は内容の承認である（差戻しに理由が要るのはそのため）。
  //   労働時間を 1 つも見せずに承認ボタンを出すと、その承認は
  //   「何を見て承認したのか」に答えられない証跡にしかならない
  const [settlement, setSettlement] = useState<MonthlySettlement | null>(null);
  const [days, setDays] = useState<readonly DailyAttendance[]>([]);
  const [reason, setReason] = useState('');
  const [problem, setProblem] = useState<Presentation | null>(null);
  const [busy, setBusy] = useState(false);

  const begin = useFreshness();
  const reload = useCallback(async () => {
    // ★ 月を切り替えると前の月の問い合わせがまだ飛んでいる
    const isFresh = begin();
    try {
      const rows = await get<readonly PendingApproval[]>(
        `/api/monthly-attendances/pending-approval?month=${month}`);
      if (isFresh()) setPending(rows);
    } catch (error) {
      if (isFresh()) setProblem(presentationOf(error));
    }
  }, [begin, month]);

  // ★ 月を切り替えたら開いている詳細を閉じる。
  //   残すと、別の月の内容を見ながら決裁することになる
  useEffect(() => { setSelected(null); void reload(); }, [reload]);

  async function open(row: { employeeId: string }) {
    setProblem(null);
    setReason('');
    try {
      setSelected(await get<MonthlyAttendance>(
        `/api/employees/${row.employeeId}/monthly-attendances/${month}`));
    } catch (error) {
      setProblem(presentationOf(error));
      return;
    }
    // ★ 中身は開いた 1 人ぶんだけ引く。一覧の行ごとに引くと、
    //   社員数ぶんの問い合わせと認可判定が重複する
    const { from, toExclusive } = monthRangeOf(month);
    const [settlementResult, daysResult] = await Promise.allSettled([
      get<MonthlySettlement>(`/api/employees/${row.employeeId}/settlements/${month}`),
      get<AttendanceList>(`/api/employees/${row.employeeId}`
        + `/attendances?from=${from}&toExclusive=${toExclusive}`),
    ]);
    // 月次清算はまだ計算されていないことがある。404 は正常なので握る
    setSettlement(settlementResult.status === 'fulfilled' ? settlementResult.value : null);
    setDays(daysResult.status === 'fulfilled' ? daysResult.value.days : []);
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
        // ★ 月は開いている対象から取る。画面の月セレクタから取ると、
        //   詳細を開いたまま月を切り替えたときに
        //   「表示している月と違う月」を承認・差戻ししてしまう
        `/api/employees/${selected.employeeId}/monthly-attendances/${selected.month}/${path}`,
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
                <tr><th>社員</th><th>状態</th><th>提出</th><th /></tr>
              </thead>
              <tbody>
                {pending.map((row) => (
                  <tr key={row.employeeId}>
                    {/* ★ 氏名は返らない。employee が所有する概念なので混ぜない */}
                    <td>{row.employeeId}</td>
                    <td>{stateLabel(row.status)}</td>
                    {/* ★ 版は載らない。決裁するのは開いた 1 人だけなので、
                          版は詳細から取る */}
                    <td>
                      {row.submittedAt.slice(0, 16).replace('T', ' ')}
                      {row.proxySubmitted && <strong>　（人事の代理提出）</strong>}
                    </td>
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
          <h2>{selected.employeeId} の {selected.month}</h2>
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

          <WorkingTime settlement={settlement} days={days} />

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

/**
 * 承認者が見るべき中身。
 *
 * ★ **休憩不足はここで示す。** 承認待ち一覧に項目として持たせない
 *   （同じ事実は日次の `breakRequirementSatisfied` が持っており、
 *   2 か所に置くと BR-08 の判定を直したときに片方だけが古くなる）。
 *
 * ★ 時間外は月次と日次で別物である。月次はフレックスでは日次から導けないので、
 *   月次清算の値をそのまま出す。日次は 2 列のまま出す（落とし穴 115）。
 */
function WorkingTime({ settlement, days }: {
  settlement: MonthlySettlement | null;
  days: readonly DailyAttendance[];
}) {
  const shortBreaks = days.filter((day) => !day.breakRequirementSatisfied);
  return (
    <>
      <h2>労働時間</h2>
      {settlement === null
        ? <p className="muted">月次清算がまだ計算されていません。</p>
        : (
          <p className="muted">
            実労働 {hoursLabel(settlement.workingMinutes)}
            {' ／ '}所定総 {hoursLabel(settlement.scheduledTotalMinutes)}
            {' ／ '}時間外 {hoursLabel(settlement.overtimeMinutes)}
            {' ／ '}不足 {hoursLabel(settlement.shortageMinutes)}
          </p>
        )}
      {shortBreaks.length > 0 && (
        <div className="warning" role="alert">
          法定休憩に不足している日があります（BR-08）：
          {shortBreaks.map((day) => shortDateOf(day.workDate)).join('、')}
        </div>
      )}
      {days.length === 0
        ? <p className="muted">計算済みの日がありません。</p>
        : (
          <table>
            <thead>
              <tr>
                <th>日</th><th className="num">実労働</th><th className="num">休憩</th>
                <th className="num">所定内</th><th className="num">法定内残業</th>
                <th className="num">法定外残業</th><th className="num">深夜</th>
                <th className="num">法定休日</th>
              </tr>
            </thead>
            <tbody>
              {days.map((day) => (
                <tr key={day.workDate}>
                  <td>{shortDateOf(day.workDate)}</td>
                  <td className="num">{hoursLabel(day.workingMinutes)}</td>
                  <td className="num">{hoursLabel(day.breakMinutes)}</td>
                  <td className="num">{hoursLabel(day.baseMinutes)}</td>
                  <td className="num">
                    {hoursLabel(day.overtimeWithinStatutoryMinutes)}
                  </td>
                  <td className="num">
                    {hoursLabel(day.overtimeBeyondStatutoryMinutes)}
                  </td>
                  <td className="num">{hoursLabel(day.nightMinutes)}</td>
                  <td className="num">{hoursLabel(day.legalHolidayMinutes)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
    </>
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
