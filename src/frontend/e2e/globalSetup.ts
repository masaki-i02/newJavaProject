import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

/**
 * シナリオの前提データを毎回入れ直す。
 *
 * ★ 手順書に「先に seed.sql を流す」と書くだけにしない。
 *   忘れると、2 回目の実行が「すでに退勤済み」から始まり、
 *   IT-SCN-32 が製品の欠陥のように見える形で落ちる。
 *   実際に一度そうなった。**通せる回数が 1 回だけのシナリオは、
 *   通らなくなった理由を切り分けられない。**
 *
 * ★ `psql` が無い環境では、黙って続けずに落とす。
 *   前提データが入っていない状態で走らせても、
 *   落ちた理由が「製品の欠陥」と見分けられない。
 */
export default function globalSetup(): void {
  const here = dirname(fileURLToPath(import.meta.url));
  const seed = join(here, 'seed.sql');
  const env = {
    ...process.env,
    PGPASSWORD: process.env['KINTAI_E2E_DB_PASSWORD'] ?? 'kintai',
  };
  try {
    execFileSync('psql', [
      '-h', process.env['KINTAI_E2E_DB_HOST'] ?? '127.0.0.1',
      '-p', process.env['KINTAI_E2E_DB_PORT'] ?? '55432',
      '-U', process.env['KINTAI_E2E_DB_USER'] ?? 'kintai',
      '-d', process.env['KINTAI_E2E_DB_NAME'] ?? 'kintai_e2e',
      '-v', 'ON_ERROR_STOP=1', '-q', '-f', seed,
    ], { env, stdio: 'pipe' });
  } catch (error) {
    throw new Error(
      `前提データを入れられませんでした。psql と接続先を確認してください。\n`
      + `手動で流す場合: psql -h 127.0.0.1 -p 55432 -U kintai -d kintai_e2e -f ${seed}\n`
      + String(error));
  }
}
