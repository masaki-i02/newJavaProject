import type { Problem } from './problem';

/**
 * API を叩く薄いラッパ。
 *
 * ★ セッション Cookie + CSRF である。
 *   - `credentials: 'same-origin'` が要る（同一オリジンで配信するので `include` は不要）
 *   - 更新系は `XSRF-TOKEN` クッキーを読んで `X-XSRF-TOKEN` ヘッダへ載せる
 *
 * ★ `SecurityConfig` の `csrfHandler.setCsrfRequestAttributeName(null)` に依存している。
 *   Spring Security 6 以降、CSRF トークンは既定で遅延ロードされる。
 *   あの 1 行がそれを無効にしているので、`POST /api/sessions`（CSRF 検証は除外）でも
 *   クッキーが必ず発行される。**あの行を消すと、ログイン直後の最初の POST が
 *   必ず 403 になる。** ログイン自体は成功するので「たまに 403」に見えて原因を追いにくい。
 */
export class ApiError extends Error {
  constructor(readonly problem: Problem) {
    super(problem.title);
    this.name = 'ApiError';
  }
}

/** 401 は本文を持たない（`HttpStatusEntryPoint` はステータスだけを返す）。 */
const UNAUTHENTICATED: Problem = {
  type: 'urn:kintai:error:authentication-failed',
  title: 'ログインが必要です',
  status: 401,
};

export async function get<T>(path: string): Promise<T> {
  return request<T>('GET', path);
}

export async function post<T>(path: string, body?: unknown): Promise<T> {
  return request<T>('POST', path, body);
}

/**
 * 冪等な更新（暦日区分の設定）。
 *
 * ★ `POST` で代用しない。同じ日を 2 回設定しても結果が同じ操作なので、
 *   経路も動詞もサーバの定義（`PUT /api/calendars/{date}`）にそろえる。
 *   ずらすと、設計書と実装の突き合わせ（`check-endpoints.py`）を通ったまま
 *   画面だけが 405 を受ける。
 */
export async function put<T>(path: string, body?: unknown): Promise<T> {
  return request<T>('PUT', path, body);
}

/** 一部だけを更新する（社員の氏名とメール）。 */
export async function patch<T>(path: string, body?: unknown): Promise<T> {
  return request<T>('PATCH', path, body);
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {};
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  const csrf = csrfToken();
  if (csrf !== undefined) {
    headers['X-XSRF-TOKEN'] = csrf;
  }

  const response = await fetch(path, {
    method,
    credentials: 'same-origin',
    headers,
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });

  if (response.ok) {
    // 204 と CSV は本文の形が違う。呼ぶ側が型を決める
    if (response.status === 204) {
      return undefined as T;
    }
    return (await response.json()) as T;
  }

  // ★ 401 は本文を読まない。空なので `response.json()` が SyntaxError になる
  if (response.status === 401) {
    throw new ApiError(UNAUTHENTICATED);
  }
  throw new ApiError(await problemOf(response));
}

/**
 * 応答から Problem Details を取り出す。
 *
 * ★ CSRF の検証失敗（403）は Problem Details ではない。
 *   フィルタ層で `sendError` になるので、Spring Boot の既定のエラー応答が返る。
 *   業務上の 403（`urn:kintai:error:forbidden`）とは JSON の形が違うので、
 *   `type` があることを前提にすると落ちる。
 */
async function problemOf(response: Response): Promise<Problem> {
  try {
    const body = (await response.json()) as Partial<Problem>;
    if (typeof body.type === 'string' && typeof body.title === 'string') {
      return { ...body, type: body.type, title: body.title, status: response.status };
    }
  } catch {
    // 本文が JSON でない。既定のエラー応答か、プロキシが返したもの
  }
  return {
    type: 'about:blank',
    title: 'エラーが発生しました',
    status: response.status,
  };
}

/**
 * `XSRF-TOKEN` クッキーを読む。
 *
 * `CookieCsrfTokenRepository.withHttpOnlyFalse()` なので JavaScript から読める。
 * 読めるようにしてあるのは、SPA がヘッダへ載せるためである。
 */
export function csrfToken(): string | undefined {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
  return match?.[1] === undefined ? undefined : decodeURIComponent(match[1]);
}
