import { useCallback, useEffect, useState } from 'react';

import { get, post } from '../api/client';
import { useFreshness } from '../api/freshness';
import type { Presentation } from '../api/problem';
import type {
  AttendanceState, BulkClosureResult, ClosureStatus, SkippedClosure, UnassignedWorkRules,
} from '../api/types';
import { monthRangeOf, previousDayOf, type YearMonth } from '../api/wallClock';
import { ProblemBanner } from './ProblemBanner';
import { stateLabel } from './MyMonth';
import { presentationOf } from './Punch';

/**
 * SC-08 月次締め（画面設計書 4.4）。
 *
 * ★ **未提出・未承認が残っている状態を可視化する。** 締めてから気づいても遅い。
 *
 * ★ 一括締めは `POST /api/monthly-attendances/bulk-closure` を **1 回**呼ぶ。
 *   画面から社員ごとに `closure` を繰り返さない。途中で失敗したときに、
 *   どこまで締まったかが利用者にも画面にも分からなくなる。
 *
 * ★ 「締められるか」を画面が判定しない。`canClose` をサーバが返す。
 *   `status === 'APPROVED'` と書くと、人事であることと対象月が終わっていることが
 *   画面から落ちて、押せるのに 409 になるボタンが出る。
 *
 * ★ 節ごとに失敗を持つ。就業規則の未設定が取れなかったときに、
 *   「未設定の社員はいません」と表示して失敗を成功に見せない（落とし穴 145）。
 */
export function Closure({ month }: { month: YearMonth }) {
  const [rows, setRows] = useState<readonly ClosureStatus[]>([]);
  const [unassigned, setUnassigned] = useState<readonly string[]>([]);
  const [problem, setProblem] = useState<Presentation | null>(null);
  const [unassignedProblem, setUnassignedProblem] = useState<Presentation | null>(null);
  const [result, setResult] = useState<BulkClosureResult | null>(null);
  const [busy, setBusy] = useState(false);

  const begin = useFreshness();
  const reload = useCallback(async () => {
    // ★ 月を切り替えると前の月の問い合わせがまだ飛んでいる
    const isFresh = begin();
    // ★ 就業規則の未設定は基準日で訊く。既定（当日）のまま訊くと、
    //   5 月に 4 月分を締めるときに「5/1 入社で規則未適用の社員」が現れ、
    //   4 月とは無関係な社員を人事に見せることになる。
    //   基準日は対象月の末日（＝清算期間の最終日）にそろえる
    const lastDay = previousDayOf(monthRangeOf(month).toExclusive);
    const [statuses, unassignedResult] = await Promise.allSettled([
      get<readonly ClosureStatus[]>(`/api/monthly-attendances?month=${month}`),
      get<UnassignedWorkRules>(`/api/work-rule-assignments/unassigned?date=${lastDay}`),
    ]);
    if (!isFresh()) return;
    if (statuses.status === 'fulfilled') {
      setRows(statuses.value);
      setProblem(null);
    } else {
      setRows([]);
      setProblem(presentationOf(statuses.reason));
    }
    if (unassignedResult.status === 'fulfilled') {
      setUnassigned(unassignedResult.value.employeeIds);
      setUnassignedProblem(null);
    } else {
      setUnassigned([]);
      setUnassignedProblem(presentationOf(unassignedResult.reason));
    }
  }, [begin, month]);

  // ★ 月を切り替えたら前の月の結果を消す。残すと、別の月の結果を見ながら締めることになる
  useEffect(() => { setResult(null); void reload(); }, [reload]);

  async function closeAll() {
    setProblem(null);
    setResult(null);
    setBusy(true);
    try {
      // ★ `employeeIds: null` で全社員。画面が対象を組み立てない
      //   （組み立てると、画面が知らない社員が締められないまま残る）
      setResult(await post<BulkClosureResult>('/api/monthly-attendances/bulk-closure',
        { month, employeeIds: null }));
      await reload();
    } catch (error) {
      // ★ 依頼そのものの不備（人事でない・対象月が終わっていない）は例外で来る。
      //   社員ごとの事情は結果の `skipped` に入る（決定表「一括操作の失敗」）
      setProblem(presentationOf(error));
    } finally {
      setBusy(false);
    }
  }

  const closable = rows.filter((row) => row.canClose);

  return (
    <main>
      <ProblemBanner presentation={problem} />

      {result !== null && (
        <div className="warning" role="status">
          {result.closed} 件を締めました。
          {result.skipped.length > 0
            && ` 締められなかった社員が ${result.skipped.length} 名います。`}
        </div>
      )}

      <section>
        <h2>{month} の締め</h2>
        <Summary rows={rows} />
        <p>
          <button className="action" disabled={busy || closable.length === 0}
                  onClick={() => void closeAll()}>
            締められる {closable.length} 名をまとめて締める
          </button>
        </p>
        {closable.length === 0 && rows.length > 0 && (
          <p className="muted">
            いま締められる社員はいません。下の一覧の「締められない理由」を確認してください。
          </p>
        )}
      </section>

      <section>
        <h2>就業規則が未設定の社員</h2>
        <ProblemBanner presentation={unassignedProblem} />
        {/* ★ 失敗しているときに「いません」と言わない（落とし穴 145） */}
        {unassignedProblem !== null ? null : unassigned.length === 0
          ? <p className="muted">いません。</p>
          : (
            <>
              <p className="muted">
                この社員は日次勤怠が計算されないので締められません。
                就業規則を適用してから、日次を再計算してください。
              </p>
              <ul>{unassigned.map((id) => <li key={id}>{id}</li>)}</ul>
            </>
          )}
      </section>

      <section>
        <h2>社員ごとの状態</h2>
        {rows.length === 0
          ? <p className="muted">対象の社員がいません。</p>
          : (
            <table>
              <thead>
                <tr><th>社員</th><th>状態</th><th>締められない理由</th></tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.employeeId}>
                    {/* ★ 氏名は返らない。employee が所有する概念なので混ぜない */}
                    <td>{row.employeeId}</td>
                    <td>{stateLabel(row.status)}</td>
                    <td>{row.canClose ? '' : row.reason ?? ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
      </section>

      {result !== null && result.skipped.length > 0 && (
        <section>
          <h2>締められなかった社員</h2>
          <table>
            <thead>
              <tr><th>社員</th><th>状態</th><th>理由</th></tr>
            </thead>
            <tbody>
              {result.skipped.map((skipped: SkippedClosure) => (
                <tr key={skipped.employeeId}>
                  <td>{skipped.employeeId}</td>
                  <td>{stateLabel(skipped.status)}</td>
                  <td>{skipped.reason}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}
    </main>
  );
}

/**
 * 状態ごとの件数。
 *
 * ★ **行から数える。** サーバに件数の列を持たせない（07 DB設計書 1 と同じ理由）。
 *   持たせると、行の集合が動いたときに件数だけが古くなる。
 */
function Summary({ rows }: { rows: readonly ClosureStatus[] }) {
  if (rows.length === 0) {
    return null;
  }
  return (
    <p className="muted">
      {STATES.map((state) => (
        <span key={state} style={{ marginRight: '1rem' }}>
          {stateLabel(state)} {rows.filter((row) => row.status === state).length} 名
        </span>
      ))}
    </p>
  );
}

/**
 * 数え上げる状態。
 *
 * ★ **`CLOSED` を落とさない。** 締めたあとに同じ画面を開くと、
 *   4 状態のうち締め済みが無いと全員がどの箱にも入らない。
 */
const STATES: readonly AttendanceState[] = ['DRAFT', 'SUBMITTED', 'APPROVED', 'CLOSED'];
