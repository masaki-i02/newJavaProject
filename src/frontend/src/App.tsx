import { useState } from 'react';

import type { SignedIn } from './api/types';
import { asYearMonth, type YearMonth } from './api/wallClock';
import { Approvals } from './screens/Approvals';
import { Calendar } from './screens/Calendar';
import { Closure } from './screens/Closure';
import { MyMonth } from './screens/MyMonth';
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
type Screen = 'punch' | 'myMonth' | 'approvals' | 'closure' | 'workRules' | 'calendar';

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
          {user.roles.includes('APPROVER') && (
            <button onClick={() => setScreen('approvals')}
                    aria-current={screen === 'approvals' ? 'page' : undefined}>承認</button>
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
      {screen === 'approvals' && <Approvals month={month} />}
      {screen === 'closure' && <Closure month={month} />}
      {/* ★ 就業規則は月に依存しない。系列と版の履歴を丸ごと見る画面である */}
      {screen === 'workRules' && <WorkRules />}
      {screen === 'calendar' && <Calendar month={month} />}
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
