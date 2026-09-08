import { useCallback, useEffect, useState } from 'react';

import { get, post } from '../api/client';
import { useFreshness } from '../api/freshness';
import type { Presentation } from '../api/problem';
import type {
  CorrectionItem, CorrectionRequest, PunchType, RecordedPunch, SignedIn,
} from '../api/types';
import { asDateTime, timeOf } from '../api/wallClock';
import { ProblemBanner } from './ProblemBanner';
import { presentationOf, punchLabel } from './Punch';

/**
 * SC-04 打刻訂正の申請（BR-09）。
 *
 * ★ **「変更」という操作を置かない。** 取消（`REVOKE`）と追加（`ADD`）の
 *   組み合わせで表す。変更を許すと元の打刻の値が失われ、
 *   「何がどう直ったのか」を利用者が確かめられなくなる。
 *
 * ★ **代理申請の口を作らない。** 訂正は本人の意思表示であり、
 *   人事でも代わりには出せない。認めると「本人が申請していない訂正」が生まれる。
 *
 * ★ 取消済みの打刻は対象に選ばせない。選べても 409 になるだけで、
 *   押せてから断られるのは操作として悪い。
 */
export function Corrections({ user }: { user: SignedIn }) {
  const [workDate, setWorkDate] = useState('');
  const [punches, setPunches] = useState<readonly RecordedPunch[]>([]);
  const [requests, setRequests] = useState<readonly CorrectionRequest[]>([]);
  const [revoking, setRevoking] = useState<readonly string[]>([]);
  const [adding, setAdding] = useState<readonly AddedPunch[]>([]);
  const [reason, setReason] = useState('');
  const [problem, setProblem] = useState<Presentation | null>(null);
  const [punchProblem, setPunchProblem] = useState<Presentation | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const begin = useFreshness();
  const reloadRequests = useCallback(async () => {
    const isFresh = begin();
    try {
      const found = await get<readonly CorrectionRequest[]>(
        `/api/employees/${user.id}/correction-requests`);
      if (isFresh()) setRequests(found);
    } catch (error) {
      if (isFresh()) setProblem(presentationOf(error));
    }
  }, [begin, user.id]);

  useEffect(() => { void reloadRequests(); }, [reloadRequests]);

  // 勤務日を選んだら、その日の打刻を引く
  useEffect(() => {
    if (workDate === '') {
      setPunches([]);
      return;
    }
    const isFresh = begin();
    void (async () => {
      try {
        const found = await get<readonly RecordedPunch[]>(
          `/api/employees/${user.id}/time-clocks?workDate=${workDate}`);
        if (!isFresh()) return;
        setPunches(found);
        setPunchProblem(null);
      } catch (error) {
        if (!isFresh()) return;
        setPunches([]);
        setPunchProblem(presentationOf(error));
      }
    })();
    setRevoking([]);
    setAdding([]);
  }, [begin, user.id, workDate]);

  async function submit() {
    setProblem(null);
    setNotice(null);
    setBusy(true);
    try {
      // ★ 取消と追加を 1 つの申請にまとめる。
      //   「17:00 を取り消して 19:00 にする」は 2 項目で表す
      const items: CorrectionItem[] = [
        ...revoking.map((id) => ({ action: 'REVOKE' as const, targetEventId: id })),
        // ★ 壁掛け時計時刻として**検証してから**渡す。
        //   `as` で押し込むと、`<input type="time">` が空のときに
        //   `2026-04-06T:00` という値をそのまま送ることになる
        ...adding.map((added) => ({
          action: 'ADD' as const,
          eventType: added.type,
          occurredAt: asDateTime(`${workDate}T${added.time}:00`),
        })),
      ];
      await post<CorrectionRequest>(
        `/api/employees/${user.id}/correction-requests`,
        { workDate, reason, items });
      setNotice('訂正を申請しました。承認されるまで打刻は変わりません。');
      setRevoking([]);
      setAdding([]);
      setReason('');
      await reloadRequests();
    } catch (error) {
      setProblem(presentationOf(error));
    } finally {
      setBusy(false);
    }
  }

  async function cancel(request: CorrectionRequest) {
    setProblem(null);
    setBusy(true);
    try {
      await post<CorrectionRequest>(
        `/api/correction-requests/${request.id}/cancellation`,
        { version: request.version });
      setNotice('申請を取り下げました。');
      await reloadRequests();
    } catch (error) {
      setProblem(presentationOf(error));
    } finally {
      setBusy(false);
    }
  }

  const selectable = punches.filter((punch) => !punch.revoked);
  const complete = workDate !== '' && reason.trim() !== ''
    && (revoking.length > 0 || adding.length > 0)
    && adding.every((added) => added.time !== '');

  return (
    <main>
      <ProblemBanner presentation={problem} />
      {notice !== null && <div className="warning" role="status">{notice}</div>}

      <section>
        <h2>打刻の訂正を申請する</h2>
        <p>
          <label htmlFor="cr-date">勤務日</label>{' '}
          <input id="cr-date" type="date" value={workDate}
                 onChange={(e) => setWorkDate(e.target.value)} />
        </p>

        {workDate !== '' && (
          <>
            <ProblemBanner presentation={punchProblem} />
            {punchProblem !== null ? null : punches.length === 0
              ? <p className="muted">この日の打刻はありません。</p>
              : (
                <table>
                  <thead>
                    <tr><th>種別</th><th>時刻</th><th>取消する</th></tr>
                  </thead>
                  <tbody>
                    {punches.map((punch) => (
                      <tr key={punch.id}>
                        <td>{punchLabel(punch.type)}</td>
                        <td>{timeOf(punch.occurredAt)}</td>
                        <td>
                          {/* ★ 取消済みは選ばせない。押せても 409 になるだけである。
                                ただし行は残す（何がどう直ったかを示すため）*/}
                          {punch.revoked
                            ? <span className="muted">取消済み</span>
                            : (
                              <input type="checkbox"
                                     aria-label={`${timeOf(punch.occurredAt)} を取り消す`}
                                     checked={revoking.includes(punch.id)}
                                     onChange={(e) => setRevoking(e.target.checked
                                       ? [...revoking, punch.id]
                                       : revoking.filter((id) => id !== punch.id))} />
                            )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}

            <h2>追加する打刻</h2>
            {adding.map((added, index) => (
              <p key={index}>
                <select aria-label={`追加する打刻の種別 ${index + 1}`} value={added.type}
                        onChange={(e) => setAdding(adding.map((a, i) => i === index
                          ? { ...a, type: asPunchType(e.target.value) } : a))}>
                  <option value="CLOCK_IN">出勤</option>
                  <option value="BREAK_START">休憩開始</option>
                  <option value="BREAK_END">休憩終了</option>
                  <option value="CLOCK_OUT">退勤</option>
                </select>
                {' '}
                <input type="time" aria-label={`追加する打刻の時刻 ${index + 1}`}
                       value={added.time}
                       onChange={(e) => setAdding(adding.map((a, i) => i === index
                         ? { ...a, time: e.target.value } : a))} />
                {' '}
                <button className="action secondary"
                        onClick={() => setAdding(adding.filter((_, i) => i !== index))}>
                  やめる
                </button>
              </p>
            ))}
            <p>
              <button className="action secondary" disabled={busy}
                      onClick={() => setAdding([...adding,
                        { type: 'CLOCK_OUT', time: '' }])}>
                打刻を追加する
              </button>
            </p>

            <p>
              {/* ★ 理由は必須。何を直したいのかが分からない申請は承認できない */}
              <label htmlFor="cr-reason">申請の理由（必須）</label><br />
              <input id="cr-reason" value={reason} size={60}
                     onChange={(e) => setReason(e.target.value)} />
            </p>
            <button className="action" disabled={busy || !complete}
                    onClick={() => void submit()}>
              訂正を申請する
            </button>
            {selectable.length === 0 && punches.length > 0 && (
              <p className="muted">
                この日の打刻はすべて取消済みです。追加だけの申請はできます。
              </p>
            )}
          </>
        )}
      </section>

      <section>
        <h2>自分の申請</h2>
        {/* ★ 決着したものも含めて並べる。承認待ちだけにすると、
              却下された申請が画面から消えて理由が読めなくなる */}
        {requests.length === 0
          ? <p className="muted">申請はありません。</p>
          : (
            <table>
              <thead>
                <tr><th>勤務日</th><th>状態</th><th>理由</th><th>内容</th><th /></tr>
              </thead>
              <tbody>
                {requests.map((request) => (
                  <tr key={request.id}>
                    <td>{request.workDate}</td>
                    <td>{correctionStatusLabel(request.status)}</td>
                    <td>{request.reason}</td>
                    <td>{request.items.map(itemLabel).join('、')}</td>
                    <td>
                      {/* ★ 取り下げられるのは申請中のものだけ。
                            サーバも同じ判断で 409 を返す */}
                      {request.status === 'SUBMITTED' && (
                        <button className="action secondary" disabled={busy}
                                onClick={() => void cancel(request)}>
                          取り下げる
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
      </section>
    </main>
  );
}

interface AddedPunch {
  readonly type: PunchType;
  readonly time: string;
}

/** 訂正申請の状態。網羅性検査つきの `switch`（`default` を置かない）。 */
export function correctionStatusLabel(status: CorrectionRequest['status']): string {
  switch (status) {
    case 'SUBMITTED': return '申請中';
    case 'APPROVED': return '承認済み';
    case 'REJECTED': return '却下';
    case 'CANCELED': return '取下げ';
  }
}

/**
 * 訂正の 1 項目の表示。
 *
 * ★ 判別子で絞る。平坦な型だと「REVOKE なのに occurredAt を読む」経路が
 *   コンパイルを通ってしまう。
 */
export function itemLabel(item: CorrectionItem): string {
  switch (item.action) {
    case 'REVOKE': return '打刻の取消';
    case 'ADD': return `${punchLabel(item.eventType)} ${timeOf(item.occurredAt)} を追加`;
  }
}

/** `<select>` の値を打刻の種別へ写す。`as` で押し込まない。 */
export function asPunchType(value: string): PunchType {
  switch (value) {
    case 'CLOCK_IN': return 'CLOCK_IN';
    case 'BREAK_START': return 'BREAK_START';
    case 'BREAK_END': return 'BREAK_END';
    case 'CLOCK_OUT': return 'CLOCK_OUT';
    default: throw new Error(`打刻の種別ではありません: ${value}`);
  }
}
