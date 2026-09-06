import { useState } from 'react';

import { post } from '../api/client';
import { ApiError } from '../api/client';
import { present, type Presentation } from '../api/problem';
import type { SignedIn } from '../api/types';
import { ProblemBanner } from './ProblemBanner';

/**
 * SC-01 ログイン。
 *
 * ★ 認証 ID は社員番号である（要件 7）。
 *   メールは退職者への再割り当てと衝突する。
 *
 * ★ 失敗の理由を画面でも区別しない。
 *   サーバが区別せずに返すので、画面が区別しようとしても情報が無い。
 */
export function SignIn({ onSignedIn }: { onSignedIn: (user: SignedIn) => void }) {
  const [employeeNumber, setEmployeeNumber] = useState('');
  const [password, setPassword] = useState('');
  const [problem, setProblem] = useState<Presentation | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setProblem(null);
    setSubmitting(true);
    try {
      onSignedIn(await post<SignedIn>('/api/sessions', { employeeNumber, password }));
    } catch (error) {
      setProblem(error instanceof ApiError
        ? failureOf(error)
        : { kind: 'unknown', message: '通信に失敗しました' });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main>
      <section>
        <h2>ログイン</h2>
        <ProblemBanner presentation={problem} />
        <form onSubmit={submit}>
          <p>
            <label htmlFor="employeeNumber">社員番号</label><br />
            <input id="employeeNumber" name="employeeNumber" autoComplete="username"
                   value={employeeNumber}
                   onChange={(e) => setEmployeeNumber(e.target.value)} />
          </p>
          <p>
            <label htmlFor="password">パスワード</label><br />
            <input id="password" name="password" type="password"
                   autoComplete="current-password" value={password}
                   onChange={(e) => setPassword(e.target.value)} />
          </p>
          <button className="action" type="submit" disabled={submitting}>
            ログイン
          </button>
        </form>
      </section>
    </main>
  );
}

/**
 * ★ ログイン画面では `signIn` へ落とさない。
 *   「ログインしてください」と案内しても、いま見ているのがログイン画面である。
 */
function failureOf(error: ApiError): Presentation {
  const presentation = present(error.problem);
  return presentation.kind === 'signIn'
    ? { kind: 'banner', title: '社員番号またはパスワードが違います' }
    : presentation;
}
