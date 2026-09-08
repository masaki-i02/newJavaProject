/**
 * RFC 9457 Problem Details。
 *
 * サーバは業務エラーを `type`（URN）で区別して返す
 * （アーキテクチャ設計書 6.2）。
 *
 * ★ `status` で分岐しない。
 *   409 には「他の利用者が先に更新した」（再読込を促す）と
 *   「対象月がまだ終わっていない」（待つしかない）が両方あり、
 *   案内がまったく違う。
 *
 * ★ 文字列の対応表で引かない。
 *   サーバ側は同じ理由で網羅性検査つきの `switch` を使っている
 *   （CLAUDE.md 落とし穴 50）。対応表だと、追加された `type` が
 *   黙って「不明なエラー」に落ちる。
 *   判別可能ユニオンにして、`type` を足したときに
 *   **ここがコンパイルエラーになる**ようにする。
 */

/**
 * **見せ方が既定と違う** `type`。
 *
 * ★ ここは「画面が案内できるエラーの一覧」ではない。
 *   一覧にすると、サーバがエラーを 1 つ足すたびに画面が
 *   「エラーが発生しました」へ落とし、**理由がいちばん要る場面で理由が消える。**
 *   実際 `invalid-work-rule-request`（就業規則の指定が不正）や
 *   `calendar-exceeds-statutory-year`（年間の所定が法定の総枠を超える）は、
 *   人事がその場で直せる誤りなのに、一覧に足し忘れると何も伝わらなかった。
 *
 * ★ 既定はバナー（サーバの `title` と `detail` をそのまま出す）である。
 *   ドメイン例外の文言は、業務の言葉で利用者に向けて書かれている。
 */
export const SPECIAL_PROBLEMS = [
  'urn:kintai:error:validation-failed',
  'urn:kintai:error:authentication-failed',
  'urn:kintai:error:optimistic-lock-failure',
  'urn:kintai:error:internal-error',
  'urn:kintai:error:constraint-violation',
] as const;

export type SpecialProblemType = (typeof SPECIAL_PROBLEMS)[number];

/** 業務エラーの名前空間。ここに属さない `type` は素性が分からない。 */
const DOMAIN_PREFIX = 'urn:kintai:error:';

/** 入力項目に紐づくエラー（400 の `errors`）。 */
export interface FieldError {
  readonly field: string;
  readonly message: string;
}

export interface Problem {
  readonly type: string;
  readonly title: string;
  readonly status: number;
  readonly detail?: string;
  readonly errors?: readonly FieldError[];
}

/** 画面での見せ方。**5 つしかない。** */
export type Presentation =
  | { readonly kind: 'field'; readonly errors: readonly FieldError[] }
  | { readonly kind: 'banner'; readonly title: string; readonly detail?: string }
  | { readonly kind: 'reload'; readonly message: string }
  | { readonly kind: 'signIn' }
  | { readonly kind: 'unknown'; readonly message: string };

/**
 * `type` で見せ方を決める。
 *
 * ★ `status` で分岐しない。409 には「他の利用者が先に更新した」と
 *   「対象月がまだ終わっていない」が両方あり、案内がまったく違う。
 *
 * ★ `default` を書かない。既知の `type` を網羅したあとで `never` へ代入するので、
 *   `SPECIAL_PROBLEMS` に足してここへ足さないとコンパイルが落ちる。
 */
export function present(problem: Problem): Presentation {
  if (!isSpecial(problem.type)) {
    // ★ 業務エラーはサーバの文言をそのまま出す。握りつぶさない。
    //   名前空間の外（`about:blank`・プロキシが返した応答・CSRF の 403）は
    //   誰が書いた文言か分からないので出さない
    return problem.type.startsWith(DOMAIN_PREFIX)
      ? bannerOf(problem)
      : { kind: 'unknown', message: 'エラーが発生しました。時間をおいて再度お試しください。' };
  }
  switch (problem.type) {
    case 'urn:kintai:error:validation-failed':
      return { kind: 'field', errors: problem.errors ?? [] };

    case 'urn:kintai:error:authentication-failed':
      return { kind: 'signIn' };

    case 'urn:kintai:error:optimistic-lock-failure':
      // ★ ここだけ案内が違う。読み直せば解決する
      return {
        kind: 'reload',
        message: '他の利用者が先に更新しました。読み直してからもう一度お試しください。',
      };

    case 'urn:kintai:error:internal-error':
    case 'urn:kintai:error:constraint-violation':
      // ★ この 2 つだけは `detail` を出さない。
      //   実装の不備と DB の制約違反を受け取るハンドラであり、
      //   制約名やテーブル名という**内部の構造**が載りうる唯一の経路である。
      //   サーバは載せない方針だが、画面の側でも落としておく
      return { kind: 'unknown', message: 'エラーが発生しました。時間をおいて再度お試しください。' };

    default:
      return exhausted(problem.type);
  }
}

function bannerOf(problem: Problem): Presentation {
  return problem.detail === undefined
    ? { kind: 'banner', title: problem.title }
    : { kind: 'banner', title: problem.title, detail: problem.detail };
}

function isSpecial(type: string): type is SpecialProblemType {
  return (SPECIAL_PROBLEMS as readonly string[]).includes(type);
}

/**
 * 網羅していないと、ここでコンパイルが落ちる。
 *
 * サーバ側の `DomainErrorKind` に対する `switch` と同じ役割である。
 */
function exhausted(value: never): never {
  throw new Error(`未処理の Problem type: ${String(value)}`);
}
