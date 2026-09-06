import type { Presentation } from '../api/problem';

/**
 * エラーの表示（画面設計書 5.1）。
 *
 * ★ 業務エラーを握りつぶさない。
 *   サーバの `title` と `detail` をそのまま出す。
 *   「エラーが発生しました」に丸めると、締め済みなのか承認者でないのかが分からない。
 */
export function ProblemBanner({ presentation }: {
  presentation: Presentation | null;
}) {
  if (presentation === null) {
    return null;
  }
  switch (presentation.kind) {
    case 'banner':
      return (
        <div className="banner" role="alert">
          <strong>{presentation.title}</strong>
          {presentation.detail !== undefined && <div>{presentation.detail}</div>}
        </div>
      );
    case 'reload':
      return <div className="banner" role="alert">{presentation.message}</div>;
    case 'unknown':
      return <div className="banner" role="alert">{presentation.message}</div>;
    case 'field':
      return (
        <div className="banner" role="alert">
          <ul>
            {presentation.errors.map((error) => (
              <li key={error.field}>
                {error.field}: {error.message}
              </li>
            ))}
          </ul>
        </div>
      );
    case 'signIn':
      // 呼ぶ側がログイン画面へ戻すので、ここでは何も出さない
      return null;
  }
}
