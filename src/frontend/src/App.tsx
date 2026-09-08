import { useState } from 'react';

import type { SignedIn } from './api/types';
import { asYearMonth, type YearMonth } from './api/wallClock';
import { AgreementAlertsScreen } from './screens/AgreementAlerts';
import { Approvals } from './screens/Approvals';
import { Calendar } from './screens/Calendar';
import { CorrectionReview } from './screens/CorrectionReview';
import { Corrections } from './screens/Corrections';
import { Closure } from './screens/Closure';
import { Employees } from './screens/Employees';
import { MyMonth } from './screens/MyMonth';
import { Organization } from './screens/Organization';
import { Punch } from './screens/Punch';
import { SignIn } from './screens/SignIn';
import { WorkRules } from './screens/WorkRules';

/**
 * 画面の切り替え。
 *
 * ★ ルータを入れない。URL を共有する必要が無い。
 *   入れると依存が 1 つ増え、認証との組み合わせを考えることになる。
 *
 * ★ メニューの出し分けは利便性であって権限ではない（画面設計書 1.1 の原則 2）。
 *   `APPROVER` を持たない利用者が承認の API を叩いても、サーバが 403 を返す。
 */
type Screen =
  | 'punch' | 'myMonth' | 'corrections' | 'approvals' | 'correctionReview'
  | 'closure' | 'workRules' | 'calendar' | 'alerts'
  | 'employees' | 'organization';

export function App() {
  const [user, setUser] = useState<SignedIn | null>(null);
  const [screen, setScreen] = useState<Screen>('punch');
  // 月の選択は最小限にする。既定は「今月」ではなく空にしない
  const [month, setMonth] = useState<YearMonth>(currentMonth());

  if (user === null) {
    return <SignIn onSignedIn={(signedIn) => {
      setUser(signedIn);
      setScreen('punch');
    }} />;
  }

  return (
    <>
      <header>
        <h1>勤怠管理システム</h1>
        <span>{user.name}（{user.employeeNumber}）</span>
        <nav>
          <button onClick={() => setScreen('punch')}
                  aria-current={screen === 'punch' ? 'page' : undefined}>打刻</button>
          <button onClick={() => setScreen('myMonth')}
                  aria-current={screen === 'myMonth' ? 'page' : undefined}>月次勤怠</button>
          {/* ★ 訂正は本人の意思表示なので、代理申請の口を作らない */}
          <button onClick={() => setScreen('corrections')}
                  aria-current={screen === 'corrections' ? 'page' : undefined}>打刻訂正</button>
          {user.roles.includes('APPROVER') && (
            <>
              <button onClick={() => setScreen('approvals')}
                      aria-current={screen === 'approvals' ? 'page' : undefined}>承認</button>
              <button onClick={() => setScreen('correctionReview')}
                      aria-current={screen === 'correctionReview' ? 'page' : undefined}>
                訂正の審査
              </button>
            </>
          )}
          {/* ★ 人事の画面。出し分けは利便性であって権限ではない。
                `HR` を持たない利用者が締めの API を叩いても、サーバが 403 を返す */}
          {user.roles.includes('HR') && (
            <>
              <button onClick={() => setScreen('closure')}
                      aria-current={screen === 'closure' ? 'page' : undefined}>締め</button>
              <button onClick={() => setScreen('workRules')}
                      aria-current={screen === 'workRules' ? 'page' : undefined}>就業規則</button>
              <button onClick={() => setScreen('calendar')}
                      aria-current={screen === 'calendar' ? 'page' : undefined}>カレンダー</button>
            </>
          )}
          {/* ★ 36 協定は承認者にも出す。超過を是正できるのは、
                業務の配分を変えられる上長だけである。
                見える範囲は API が閲覧範囲で絞る */}
          {(user.roles.includes('HR') || user.roles.includes('APPROVER')) && (
            <button onClick={() => setScreen('alerts')}
                    aria-current={screen === 'alerts' ? 'page' : undefined}>36 協定</button>
          )}
          {user.roles.includes('ADMIN') && (
            <button onClick={() => setScreen('employees')}
                    aria-current={screen === 'employees' ? 'page' : undefined}>社員</button>
          )}
          {/* ★ 組織図は誰でも開ける。見える範囲は API が絞る（画面設計書 4.7） */}
          <button onClick={() => setScreen('organization')}
                  aria-current={screen === 'organization' ? 'page' : undefined}>組織図</button>
          {/* ★ 空の値を検証へ渡さない。`<input type="month">` は値を消せるので、
                  そのまま渡すと同期例外で画面ごと落ちる。
                  消したときは月を変えない（前の月のまま） */}
          <input type="month" value={month} aria-label="対象月"
                 onChange={(e) => {
                   const value = e.target.value;
                   if (value !== '') setMonth(asYearMonth(value));
                 }} />
        </nav>
      </header>
      {screen === 'punch' && <Punch user={user} />}
      {screen === 'myMonth' && <MyMonth user={user} month={month} />}
      {/* ★ 訂正の申請と審査は月に依存しない。勤務日を直接指す */}
      {screen === 'corrections' && <Corrections user={user} />}
      {screen === 'approvals' && <Approvals month={month} />}
      {screen === 'correctionReview' && <CorrectionReview />}
      {screen === 'closure' && <Closure month={month} />}
      {/* ★ 就業規則は月に依存しない。系列と版の履歴を丸ごと見る画面である */}
      {screen === 'workRules' && <WorkRules />}
      {screen === 'calendar' && <Calendar month={month} />}
      {screen === 'alerts' && <AgreementAlertsScreen month={month} />}
      {/* ★ 社員一覧と組織図は月に依存しない */}
      {screen === 'employees' && <Employees />}
      {screen === 'organization' && <Organization />}
    </>
  );
}

/**
 * 既定の対象月。
 *
 * ★ ここだけは端末の時計を使う。**表示の初期値にしか使わない。**
 *   業務の判断（対象月が終わっているか）はサーバが `Clock` から行う。
 */
function currentMonth(): YearMonth {
  const now = new Date();
  return asYearMonth(
    `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`);
}
