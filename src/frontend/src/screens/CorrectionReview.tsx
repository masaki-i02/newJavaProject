import { useCallback, useEffect, useState } from 'react';

import { get, post } from '../api/client';
import { useFreshness } from '../api/freshness';
import type { Presentation } from '../api/problem';
import type { CorrectionApproval, CorrectionRequest } from '../api/types';
import { ProblemBanner } from './ProblemBanner';
import { correctionStatusLabel, itemLabel } from './Corrections';
import { stateLabel } from './MyMonth';
import { presentationOf } from './Punch';

/**
 * SC-07 訂正申請の審査（BR-09 / BR-11）。
 *
 * ★ **承認すると打刻が書き換わり、日次と月次が計算し直される。**
 *   提出済みだった月は下書きへ戻るので、そのことを承認者に伝える。
 *   伝えないと、再提出が忘れられたまま月末を迎える。
 *
 * ★ 決裁は詳細から取った版を送る。一覧は版を返さない
 *   （行ごとに引くと閲覧範囲の判定が重複する）。
 *
 * ★ 自己承認の禁止も承認者かどうかも、サーバが判断して返す。
 *   画面に条件を書かない。
 */
export function CorrectionReview() {
  const [pending, setPending] = useState<readonly CorrectionRequest[]>([]);
  const [selected, setSelected] = useState<CorrectionRequest | null>(null);
  const [reason, setReason] = useState('');
  const [problem, setProblem] = useState<Presentation | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const begin = useFreshness();
  const reload = useCallback(async () => {
    const isFresh = begin();
    try {
      const found = await get<readonly CorrectionRequest[]>(
        '/api/correction-requests/pending-approval');
      if (isFresh()) setPending(found);
    } catch (error) {
      if (isFresh()) setProblem(presentationOf(error));
    }
  }, [begin]);

  useEffect(() => { void reload(); }, [reload]);

  async function open(id: string) {
    setProblem(null);
    setReason('');
    try {
      // ★ 版は詳細から取る。一覧の行には入っていない
      setSelected(await get<CorrectionRequest>(`/api/correction-requests/${id}`));
    } catch (error) {
      setProblem(presentationOf(error));
    }
  }

  async function decide(path: 'approval' | 'rejection') {
    if (selected === null) return;
    setProblem(null);
    setNotice(null);
    setBusy(true);
    try {
      if (path === 'approval') {
        const result = await post<CorrectionApproval>(
          `/api/correction-requests/${selected.id}/approval`,
          { version: selected.version });
        setSelected(result.request);
        // ★ 月次勤怠が下書きへ戻ったことを必ず伝える
        setNotice(`訂正を承認しました。${selected.workDate} の月次勤怠は`
          + `${stateLabel(result.monthlyAttendanceStatus)}です。`);
      } else {
        setSelected(await post<CorrectionRequest>(
          `/api/correction-requests/${selected.id}/rejection`,
          { version: selected.version, reason }));
        setNotice('訂正を却下しました。');
      }
      await reload();
    } catch (error) {
      setProblem(presentationOf(error));
      // 楽観ロックで弾かれたときは、読み直さないと次も必ず失敗する
      await open(selected.id);
    } finally {
      setBusy(false);
    }
  }

  return (
    <main>
      <ProblemBanner presentation={problem} />
      {notice !== null && <div className="warning" role="status">{notice}</div>}

      <section>
        <h2>訂正申請の承認待ち</h2>
        {pending.length === 0
          ? <p className="muted">承認待ちはありません。</p>
          : (
            <table>
              <thead>
                <tr><th>社員</th><th>勤務日</th><th>理由</th><th>内容</th><th /></tr>
              </thead>
              <tbody>
                {pending.map((request) => (
                  <tr key={request.id}>
                    {/* ★ 氏名は返らない。employee が所有する概念なので混ぜない */}
                    <td>{request.employeeId}</td>
                    <td>{request.workDate}</td>
                    <td>{request.reason}</td>
                    <td>{request.items.map(itemLabel).join('、')}</td>
                    <td>
                      <button className="action secondary"
                              onClick={() => void open(request.id)}>開く</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
      </section>

      {selected !== null && (
        <section>
          <h2>{selected.workDate} の訂正</h2>
          <p className="muted">
            状態：{correctionStatusLabel(selected.status)} ／ 版：{selected.version}
          </p>
          <p>理由：{selected.reason}</p>
          <ul>
            {selected.items.map((item, index) => <li key={index}>{itemLabel(item)}</li>)}
          </ul>
          <p className="muted">
            承認すると打刻が書き換わり、日次と月次が計算し直されます。
            <strong>提出済みだった月は下書きへ戻ります。</strong>
          </p>
          <div>
            <button className="action"
                    disabled={busy || selected.status !== 'SUBMITTED'}
                    onClick={() => void decide('approval')}>
              承認する
            </button>
            <button className="action secondary"
                    disabled={busy || selected.status !== 'SUBMITTED'
                              || reason.trim().length === 0}
                    onClick={() => void decide('rejection')}>
              却下する
            </button>
          </div>
          <p>
            <label htmlFor="cr-reject">却下の理由（必須）</label><br />
            <input id="cr-reject" value={reason} size={60}
                   onChange={(e) => setReason(e.target.value)} />
          </p>
        </section>
      )}
    </main>
  );
}
