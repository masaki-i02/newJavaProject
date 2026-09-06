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

/** 画面が個別に案内する必要のあるエラー。 */
export const KNOWN_PROBLEMS = [
  'urn:kintai:error:validation-failed',
  'urn:kintai:error:authentication-failed',
  'urn:kintai:error:forbidden',
  'urn:kintai:error:not-approver',
  'urn:kintai:error:optimistic-lock-failure',
  'urn:kintai:error:month-already-closed',
  'urn:kintai:error:month-not-editable',
  'urn:kintai:error:month-not-finished',
  'urn:kintai:error:invalid-time-clock-sequence',
  'urn:kintai:error:self-approval',
] as const;

export type KnownProblemType = (typeof KNOWN_PROBLEMS)[number];

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

/** 画面での見せ方。**4 つしかない。** */
export type Presentation =
  | { readonly kind: 'field'; readonly errors: readonly FieldError[] }
  | { readonly kind: 'banner'; readonly title: string; readonly detail?: string }
  | { readonly kind: 'reload'; readonly message: string }
  | { readonly kind: 'signIn' }
  | { readonly kind: 'unknown'; readonly message: string };

/**
 * `type` で見せ方を決める。
 *
 * ★ `default` を書かない。
 *   代わりに、既知の `type` を網羅したあとで `never` へ代入する。
 *   `KNOWN_PROBLEMS` に足したのにここへ足さないと、コンパイルが落ちる。
 */
export function present(problem: Problem): Presentation {
  if (!isKnown(problem.type)) {
    // 未知の `type` は 5xx と同じ扱いにする。`detail` は捨てる
    //（内部の構造が載っている可能性があるため）
    return { kind: 'unknown', message: 'エラーが発生しました。時間をおいて再度お試しください。' };
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

    case 'urn:kintai:error:forbidden':
    case 'urn:kintai:error:not-approver':
    case 'urn:kintai:error:self-approval':
    case 'urn:kintai:error:month-already-closed':
    case 'urn:kintai:error:month-not-editable':
    case 'urn:kintai:error:month-not-finished':
    case 'urn:kintai:error:invalid-time-clock-sequence':
      // 業務エラーはサーバの文言をそのまま出す。握りつぶさない
      return bannerOf(problem);

    default:
      return exhausted(problem.type);
  }
}

function bannerOf(problem: Problem): Presentation {
  return problem.detail === undefined
    ? { kind: 'banner', title: problem.title }
    : { kind: 'banner', title: problem.title, detail: problem.detail };
}

function isKnown(type: string): type is KnownProblemType {
  return (KNOWN_PROBLEMS as readonly string[]).includes(type);
}

/**
 * 網羅していないと、ここでコンパイルが落ちる。
 *
 * サーバ側の `DomainErrorKind` に対する `switch` と同じ役割である。
 */
function exhausted(value: never): never {
  throw new Error(`未処理の Problem type: ${String(value)}`);
}
