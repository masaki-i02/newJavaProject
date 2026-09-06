# 通しのシナリオ

**実物のバックエンドと PostgreSQL に対して通す。**

```bash
# 1. DB を起動する
bash ../../doc/_tools/start-postgres.sh
createdb -h 127.0.0.1 -p 55432 -U kintai kintai_e2e   # 初回のみ

# 2. バックエンドを起動する（別の端末）
cd ../backend && KINTAI_DB_URL=jdbc:postgresql://127.0.0.1:55432/kintai_e2e \
  KINTAI_DB_USER=kintai KINTAI_DB_PASSWORD=kintai ./gradlew bootRun

# 3. 初期データを入れる（社員 2 人・部署・就業規則・カレンダー）
psql -h 127.0.0.1 -p 55432 -U kintai -d kintai_e2e -f e2e/seed.sql

# 4. 開発サーバとシナリオ
npm run dev &
npm run e2e
```

> **`kintai_test` を使わない。** バックエンドを起動したまま
> `./gradlew test` を流すと、同じ DB を 2 つのプロセスが掴んで
> デッドロックと制約違反が大量に出る（CLAUDE.md 落とし穴 131）。

> **ブラウザは環境にあるものを使う。** `playwright.config.ts` の
> `executablePath` が `/opt/pw-browsers/chromium` を指している。
> `@playwright/test` が要求するビルド番号と環境のビルド番号は一致せず、
> `playwright install` はこの環境では取得できない。
> 別の場所にあるなら `KINTAI_E2E_CHROMIUM` で差し替える。

**モックを置かない。** 置くと、画面設計書 1.1 の原則 1
（業務ルールを画面に複製しない）が守れているかを 1 行も検査しないテストになる。
サーバが返す `availableActions` と `canSubmit` を実際に受け取ることに意味がある。
