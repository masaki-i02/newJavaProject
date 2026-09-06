import { useCallback, useEffect, useState } from 'react';

import { get, post } from '../api/client';
import type { Presentation } from '../api/problem';
import type {
  AttendanceList, DailyAttendance, MonthlyAttendance, MonthlySettlement, SignedIn,
} from '../api/types';
import { asDate, closedRangeLabel, monthRangeOf, shortDateOf, type YearMonth }
  from '../api/wallClock';
import { ProblemBanner } from './ProblemBanner';
import { presentationOf } from './Punch';

/**
 * SC-03 自分の月次勤怠。
 *
 * ★ 3 本の API を並行に呼び、それぞれ独立に描画する。
 *   まとめる BFF を作らない。まとめると、どのコンテキストが何を所有しているかが
 *   画面から見えなくなる（画面設計書 4.2）。
 *
 * ★ 提出ボタンの活性は `canSubmit` が決める。
 *   「下書きなら押せる」と書くと、対象月が終わったかどうかや
 *   未確定の日が無いかどうかまで画面に複製することになる。
 */
export function MyMonth({ user, month }: { user: SignedIn; month: YearMonth }) {
  const [days, setDays] = useState<readonly DailyAttendance[]>([]);
  const [settlement, setSettlement] = useState<MonthlySettlement | null>(null);
  const [attendance, setAttendance] = useState<MonthlyAttendance | null>(null);
  const [problem, setProblem] = useState<Presentation | null>(null);
  // ★ 節ごとに失敗を持つ。1 つにまとめると、日ごとの実績が取れなかったときに
  //   「計算済みの日がありません」と表示され、失敗が成功として見える
  const [daysProblem, setDaysProblem] = useState<Presentation | null>(null);
  const [busy, setBusy] = useState(false);

  const reload = useCallback(async () => {
    const { from, toExclusive } = monthRangeOf(month);
    // ★ 期間は半開区間で渡す（原則 3）。`atEndOfMonth` にすると月末日が漏れる
    const [daysResult, settlementResult, attendanceResult] = await Promise.allSettled([
      get<AttendanceList>(
        `/api/employees/${user.id}/attendances?from=${from}&toExclusive=${toExclusive}`),
      get<MonthlySettlement>(`/api/employees/${user.id}/settlements/${month}`),
      get<MonthlyAttendance>(
        `/api/employees/${user.id}/monthly-attendances/${month}`),
    ]);
    if (daysResult.status === 'fulfilled') {
      setDays(daysResult.value.days);
      setDaysProblem(null);
    } else {
      setDays([]);
      setDaysProblem(presentationOf(daysResult.reason));
    }
    // 月次清算はまだ計算されていないことがある。404 は正常なので握る
    setSettlement(settlementResult.status === 'fulfilled' ? settlementResult.value : null);
    if (attendanceResult.status === 'fulfilled') {
      setAttendance(attendanceResult.value);
    } else {
      setProblem(presentationOf(attendanceResult.reason));
    }
  }, [user.id, month]);

  useEffect(() => { void reload(); }, [reload]);

  async function submit() {
    if (attendance === null) return;
    setProblem(null);
    setBusy(true);
    try {
      // ★ 版を必ず送る。2 人が同時に開いていたときの上書きを防ぐ
      const updated = await post<MonthlyAttendance>(
        `/api/employees/${user.id}/monthly-attendances/${month}/submission`,
        { version: attendance.version });
      // ★ 応答が新しい版と「次に何ができるか」を返すので、読み直さなくてよい
      setAttendance(updated);
    } catch (error) {
      setProblem(presentationOf(error));
      await reload();
    } finally {
      setBusy(false);
    }
  }

  return (
    <main>
      <ProblemBanner presentation={problem} />
      {attendance?.warnings?.map((warning) => (
        <div className="warning" role="status" key={warning.type}>
          <strong>{warningLabel(warning.type)}</strong>
          {warning.dates !== undefined && `：${warning.dates.map(shortDateOf).join('・')}`}
        </div>
      ))}

      <section>
        <h2>{month} の勤怠</h2>
        <p className="muted">
          状態：{stateLabel(attendance?.status ?? 'DRAFT')}
          {settlement !== null && (
            <>
              {' '}／ 清算期間：
              {/* ★ 半開区間の上限をそのまま出さない（落とし穴 10・112） */}
              {closedRangeLabel(asDate(settlement.period.from),
                                asDate(settlement.period.toExclusive))}
            </>
          )}
        </p>
        <button className="action" disabled={busy || attendance?.canSubmit !== true}
                onClick={() => void submit()}>
          提出する
        </button>
        {attendance?.canSubmit === false && attendance.status === 'DRAFT' && (
          <span className="muted">
            対象月が終わっていないか、確定していない勤務日が残っています。
          </span>
        )}
      </section>

      {settlement !== null && (
        <section>
          <h2>月の集計</h2>
          <table>
            <tbody>
              <tr><th>労働時間制度</th><td>{settlement.workingTimeSystem}</td></tr>
              <tr><th>所定総労働時間</th>
                  <td className="num">{minutes(settlement.scheduledTotalMinutes)}</td></tr>
              <tr><th>実労働時間</th>
                  <td className="num">{minutes(settlement.workingMinutes)}</td></tr>
              <tr><th>時間外労働</th>
                  <td className="num">{minutes(settlement.overtimeMinutes)}</td></tr>
              <tr><th>不足時間</th>
                  <td className="num">{minutes(settlement.shortageMinutes)}</td></tr>
            </tbody>
          </table>
        </section>
      )}

      <section>
        <h2>日ごとの実績</h2>
        <ProblemBanner presentation={daysProblem} />
        {daysProblem !== null
          ? null
          : days.length === 0
          ? <p className="muted">計算済みの日がありません。</p>
          : (
            <table>
              <thead>
                <tr>
                  <th>日</th><th className="num">実労働</th><th className="num">休憩</th>
                  <th className="num">時間外</th><th className="num">深夜</th>
                  <th className="num">法定休日</th>
                </tr>
              </thead>
              <tbody>
                {days.map((day) => (
                  <tr key={day.workDate}>
                    <td>{shortDateOf(day.workDate)}</td>
                    <td className="num">{minutes(day.workingMinutes)}</td>
                    <td className="num">{minutes(day.breakMinutes)}</td>
                    <td className="num">{minutes(day.overtimeMinutes)}</td>
                    <td className="num">{minutes(day.nightMinutes)}</td>
                    <td className="num">{minutes(day.legalHolidayMinutes)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
      </section>
    </main>
  );
}

/**
 * 分を `8:00` の形にする。
 *
 * ★ サーバは 1 分単位で丸めずに返す（BR-01）。表示で丸めない。
 */
export function minutes(value: number): string {
  return `${Math.floor(value / 60)}:${String(value % 60).padStart(2, '0')}`;
}

export function stateLabel(state: MonthlyAttendance['status']): string {
  switch (state) {
    case 'DRAFT': return '下書き';
    case 'SUBMITTED': return '提出済';
    case 'APPROVED': return '承認済';
    case 'CLOSED': return '締め済';
  }
}

function warningLabel(type: string): string {
  return type === 'paid-leave-date-worked'
    ? '年次有給休暇の日に出勤しています'
    : type;
}
