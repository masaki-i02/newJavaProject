import { useCallback, useEffect, useState } from 'react';

import { get } from '../api/client';
import type { Presentation } from '../api/problem';
import type { AgreementAlerts as Alerts } from '../api/types';
import type { YearMonth } from '../api/wallClock';
import { hoursLabel } from './WorkRules';
import { ProblemBanner } from './ProblemBanner';
import { presentationOf } from './Punch';

/**
 * SC-09 36 協定アラート（BR-12）。
 *
 * ★ **限度時間の対象は時間外労働だけ**である（36 条 3 項・4 項）。
 *   法定休日労働は含まない。含めるのは 6 項 2 号・3 号という別の規制で、
 *   混ぜると時間外 44 時間 + 法定休日 8 時間という**適法な月に偽の警告**が立つ
 *   （落とし穴 52）。だから画面でも `subjectMinutes` に何も足さない。
 *
 * ★ 警告であって、打刻・提出・承認を妨げない（BR-12）。
 *   この画面から何かを止める操作は無い。
 */
export function AgreementAlertsScreen({ month }: { month: YearMonth }) {
  const [alerts, setAlerts] = useState<Alerts | null>(null);
  const [problem, setProblem] = useState<Presentation | null>(null);

  const reload = useCallback(async () => {
    try {
      setAlerts(await get<Alerts>(`/api/settlements/agreement-alerts?month=${month}`));
      setProblem(null);
    } catch (error) {
      setAlerts(null);
      setProblem(presentationOf(error));
    }
  }, [month]);

  useEffect(() => { void reload(); }, [reload]);

  return (
    <main>
      <ProblemBanner presentation={problem} />
      <section>
        <h2>{month} の 36 協定アラート</h2>
        {alerts === null
          ? (problem === null ? <p className="muted">読み込み中…</p> : null)
          : (
            <>
              <p className="muted">
                月 45 時間の超過 {alerts.summary.monthlyExceeded} 名
                {' ／ '}
                年 360 時間の超過 {alerts.summary.annualExceeded} 名
                {' ／ '}
                <strong>対象は時間外労働のみ。</strong>法定休日労働は含みません
              </p>
              {/* ★ この一覧が拾う範囲だけを書く。広く書くと、出ていないことを
                    「適法である」と読ませる。実際に拾うのは AlertType の
                    MONTHLY と ANNUAL だけである */}
              <p className="muted">
                この一覧が拾うのは<strong>月 45 時間と年 360 時間の限度時間</strong>
                （36 条 3 項・4 項）だけです。
                <strong>単月 100 時間未満（6 項 2 号）は月次清算の画面</strong>に、
                <strong>2〜6 か月平均 80 時間以内（6 項 3 号）はどこにも</strong>
                出ません（要件 1.8 で対象外としています）。
                ここに出ていないことは、適法であることを意味しません。
              </p>
              {alerts.alerts.length === 0
                ? <p className="muted">超過している社員はいません。</p>
                : (
                  <table>
                    <thead>
                      <tr>
                        <th>社員</th>
                        <th className="num">時間外</th>
                        <th className="num">月の上限</th>
                        <th>月の超過</th>
                        <th className="num">年の累計（当月より前）</th>
                        <th className="num">年の上限</th>
                        <th>年の超過</th>
                      </tr>
                    </thead>
                    <tbody>
                      {alerts.alerts.map((alert) => (
                        <tr key={alert.employeeId}>
                          {/* ★ 氏名は返らない。employee が所有する概念なので混ぜない */}
                          <td>{alert.employeeId}</td>
                          <td className="num">{hoursLabel(alert.subjectMinutes)}</td>
                          <td className="num">{hoursLabel(alert.monthlyLimitMinutes)}</td>
                          <td>{alert.exceedsMonthly ? '超過' : ''}</td>
                          <td className="num">
                            {hoursLabel(alert.annualUsedBeforeMinutes)}
                          </td>
                          <td className="num">{hoursLabel(alert.annualLimitMinutes)}</td>
                          <td>{alert.exceedsAnnual ? '超過' : ''}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
            </>
          )}
      </section>
    </main>
  );
}
