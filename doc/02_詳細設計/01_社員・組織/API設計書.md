# 社員・組織 API設計書

| 項目 | 内容 |
| --- | --- |
| 文書番号 | KNT-DES-103 |
| 版 | 0.2 |
| 対象パッケージ | `jp.co.sample.kintai.employee.presentation` |
| 関連文書 | [ドメインモデル設計書](ドメインモデル設計書.md) / [DB設計書](DB設計書.md) |
| 改訂 | 0.2（2026-09-01）設計レビューの指摘を反映 |

---

## 1. 共通仕様

| 項目 | 仕様 |
| --- | --- |
| ベースパス | `/api` |
| 形式 | JSON（UTF-8） |
| 日付 | ISO 8601（`2026-04-01`） |
| 日時 | ISO 8601（`2026-04-01T09:00:00`）。**会社基準タイムゾーンの壁掛け時計時刻**。オフセットを含めない |
| 識別子 | UUID の文字列表現 |
| エラー | RFC 9457 (Problem Details) |
| 認証 | セッション（`JSESSIONID`）。未認証は 401 |

### 1.1 日時にオフセットを含めない理由

打刻や始業時刻は「会社の壁掛け時計が何時を指していたか」であり、
クライアントのタイムゾーンで解釈されるべきではない。オフセット付きで返すと、
ブラウザのタイムゾーン設定によって表示がずれる。

### 1.2 `date` パラメータの共通規則

多くのエンドポイントが基準日 `date` を受け取る。

| 規則 | 内容 |
| --- | --- |
| 既定値 | **`application` 層が `Clock` から解決した当日**。プレゼンテーション層で埋めない（AR-09） |
| 許容範囲 | 当日 + 1 年まで。これを超えると `400` |
| 未来日 | 許容する。発令済みの異動を事前に確認する運用があるため |

### 1.3 エラー応答

```json
{
  "type": "urn:kintai:error:duplicate-employee-number",
  "title": "社員番号が重複しています",
  "status": 409,
  "detail": "社員番号 E0001 は既に使用されています",
  "instance": "/api/employees",
  "errors": [ { "field": "employeeNumber", "message": "既に使用されています" } ]
}
```

| `type` | HTTP | 発生条件 |
| --- | --- | --- |
| `urn:kintai:error:resource-not-found` | 404 | 指定した社員・部署が存在しない |
| `urn:kintai:error:validation-failed` | 400 | 入力形式が不正。`errors` に項目ごとの詳細 |
| `urn:kintai:error:duplicate-employee-number` | 409 | 社員番号が在籍者と重複 |
| `urn:kintai:error:duplicate-email` | 409 | メールアドレスが在籍者と重複 |
| `urn:kintai:error:duplicate-department-code` | 409 | 部署コードが現存部署と重複 |
| `urn:kintai:error:overlapping-period` | 409 | 所属・部署長の期間が重複 |
| `urn:kintai:error:department-cycle` | 409 | 部署階層に循環が生じる |
| `urn:kintai:error:optimistic-lock-failure` | 409 | 他の利用者が先に更新した |
| `urn:kintai:error:business-rule-violation` | 422 | 形式は正しいが業務上受け付けられない |
| `urn:kintai:error:forbidden` | 403 | ロールまたは閲覧範囲の不足 |

**`conflict` のような粗い型にまとめない。**
フロントエンドが「どの項目を直せばよいか」を判断できなくなるため
（[アーキテクチャ設計書 6.2](../00_共通/アーキテクチャ設計書.md)）。

`errors` は `{ "field": string, "message": string }` の配列とする。

### 1.4 楽観ロック

更新系（`PATCH` / 一部の `POST`）はリクエストボディに `version` を必須で含める。

```json
{ "version": 3, "name": "山田 太郎" }
```

サーバが読んだ値で上書きすると、他の利用者の更新を黙って消してしまう。
不一致なら `409 optimistic-lock-failure` を返す。

---

## 2. エンドポイント一覧

| メソッド | パス | 概要 | 必要ロール |
| --- | --- | --- | --- |
| `GET` | `/api/me` | ログイン中の社員の情報とロール | 認証のみ |
| `GET` | `/api/employees` | 社員一覧 | `EMPLOYEE`（範囲はロールに依存） |
| `GET` | `/api/employees/{id}` | 社員の詳細 | `EMPLOYEE`（範囲はロールに依存） |
| `POST` | `/api/employees` | 社員の登録 | `ADMIN` |
| `PATCH` | `/api/employees/{id}` | 社員情報の更新 | `ADMIN` |
| `PUT` | `/api/employees/{id}/roles` | ロールの付与・剥奪 | `ADMIN` |
| `POST` | `/api/employees/{id}/retirement` | 退職の登録 | `ADMIN` |
| `DELETE` | `/api/employees/{id}/retirement` | 退職の取消 | `ADMIN` |
| `GET` | `/api/employees/{id}/assignments` | 所属履歴 | 本人 / 上長 / `HR` / `ADMIN` |
| `POST` | `/api/employees/{id}/assignments` | 異動の登録 | `ADMIN` |
| `GET` | `/api/employees/{id}/approver` | 指定月の承認者 | 本人 / `HR` / `ADMIN` |
| `GET` | `/api/departments` | 部署ツリー | `APPROVER` / `HR` / `ADMIN` |
| `POST` | `/api/departments` | 部署の登録 | `ADMIN` |
| `PATCH` | `/api/departments/{id}` | 部署の更新 | `ADMIN` |
| `POST` | `/api/departments/{id}/abolition` | 部署の廃止 | `ADMIN` |
| `POST` | `/api/departments/{id}/managerships` | 部署長の設定・交代 | `ADMIN` |

### 2.1 閲覧範囲（ロールによる差）

| ロール | 社員一覧の範囲 | 組織図 |
| --- | --- | --- |
| `EMPLOYEE` のみ | 自分自身のみ | **見られない**（自分の所属と承認者は `GET /api/me` と `/approver` で取得） |
| `APPROVER` | 自分 + 自分が長を務める部署の配下すべて | 自分が長を務める部署以下 |
| `HR` / `ADMIN` | 全社員 | 全社 |

要件定義書 4.1 の閲覧範囲に従う。
**組織図の全体を一般社員へ公開しない。** 誰が誰の下にいるかは人事情報であり、
勤怠の記録・提出という業務に必要ないため。

> **`APPROVER` ロールは永続化しない。**
> 「承認者かどうか」の実体は `managerships`（その日に部署長を務めているか）である。
> ロールとして別途保持すると、「部署長だがロールが無く 403」「ロールはあるが対象 0 件」
> という不整合が起きる。認証時に `managerships` から導出して権限へ反映する。

**この判定は Spring Security のロール判定だけでは表現できない。**
「自分が長を務める部署の配下か」は組織の状態に依存する業務判断であり、
`application` 層が `OrganizationChart` を用いて行う。

---

## 3. 主要なエンドポイントの詳細

### 3.1 `GET /api/me`

```json
{
  "id": "0195b000-0000-7000-8000-000000000001",
  "employeeNumber": "E0001",
  "name": "山田 太郎",
  "email": "yamada@example.com",
  "hiredOn": "2023-04-01",
  "roles": ["EMPLOYEE"],
  "department": {
    "id": "0195c000-0000-7000-8000-000000000003",
    "code": "S1A",
    "name": "第一営業課"
  }
}
```

> **労働時間制度（固定 / フレックス）はここに含めない。**
> 含めると `employee → workrule` というコンテキスト間依存図に無い依存が生まれ、
> ArchUnit の AR-06 に抵触する。フロントエンドは
> [`GET /api/work-rules/effective`](../02_就業規則・カレンダー/API設計書.md) から
> 別途取得する。**API の都合でコンテキストの境界を崩さない。**

### 3.2 `GET /api/employees`

| クエリパラメータ | 型 | 既定 | 説明 |
| --- | --- | --- | --- |
| `date` | `date` | 当日 | この日付時点の所属を付与する |
| `departmentId` | `uuid` | — | 指定部署の配下に絞る |
| `includeRetired` | `boolean` | `false` | 退職者を含めるか |

```json
{
  "employees": [
    {
      "id": "0195b000-0000-7000-8000-000000000001",
      "employeeNumber": "E0001",
      "name": "山田 太郎",
      "email": "yamada@example.com",
      "hiredOn": "2023-04-01",
      "retiredOn": null,
      "department": { "id": "...", "code": "S1A", "name": "第一営業課" }
    }
  ]
}
```

| 決定 | 理由 |
| --- | --- |
| 配列を裸で返さずオブジェクトで包む | 一度 `[...]` で公開すると、後からページングのメタ情報を足せない |
| **所属を持たない社員も返す** | 未来日入社の社員が登録直後の一覧に現れないと、管理者が登録の成否を確認できない。`department` は `null` になる |
| `departmentId` 未指定なら所属で絞らない | 全社員一覧（`HR` / `ADMIN`）の用途 |

### 3.3 `POST /api/employees`

```json
{
  "employeeNumber": "E0004",
  "name": "鈴木 一郎",
  "email": "suzuki@example.com",
  "hiredOn": "2026-10-01",
  "departmentId": "0195c000-0000-7000-8000-000000000003",
  "additionalRoles": ["APPROVER"]
}
```

| 決定 | 理由 |
| --- | --- |
| 社員番号は手入力 | 人事は既存の採番体系を持つことが多い。一意性は DB の制約が保証する |
| 項目名を `additionalRoles` とする | **`EMPLOYEE` はサーバが無条件に付与する。** 要件定義書 4 章。指定できるのは追加ロールのみ |
| 登録と同時に所属を作る | 所属が無いと承認者が決まらず、勤怠を提出できない |

| 応答 | 条件 |
| --- | --- |
| `201 Created` | `Location` ヘッダに `/api/employees/{id}` |
| `400` | 入力形式が不正 |
| `409` | 社員番号またはメールアドレスが在籍者と重複 |
| `422` | 入社日より前の日付で所属を作ろうとした等 |

### 3.4 `PATCH /api/employees/{id}`

**更新可能な項目を限定する。**

| 項目 | 更新可否 | 理由 |
| --- | --- | --- |
| `name` | ○ | 改姓がある |
| `email` | ○ | 変更がある |
| `employeeNumber` | **×** | 認証 ID を兼ねるため。変更が必要なら退職＋再登録 |
| `hiredOn` | **×** | 所属の `valid_from` との整合が崩れる。誤登録は退職取消と併せて `ADMIN` が個別対応 |
| `retiredOn` | **×** | 副作用（所属・部署長のクローズ）があるため専用エンドポイントで行う |
| `roles` | **×** | `PUT /api/employees/{id}/roles` で行う |

`version` を必須とする（1.4）。

### 3.5 `POST /api/employees/{id}/retirement`（退職の登録）

```json
{ "retiredOn": "2026-09-30", "version": 3 }
```

**副作用を伴う。1 トランザクションで実行する。**

| 処理 | 内容 |
| --- | --- |
| 1 | `employees.retired_on` を設定 |
| 2 | 開いている `assignments` を **`retiredOn` の翌日**で閉じる |
| 3 | 開いている `managerships` を **`retiredOn` の翌日**で閉じる |

**`retiredOn` の翌日で閉じるのは、退職日当日は在籍しているため**である
（[ドメインモデル設計書 2.2](ドメインモデル設計書.md)）。
当日で閉じると、最終日の勤怠の承認者が導出できなくなる。

> 3 を忘れると、**その部署に所属する全社員の承認者が退職者になり続ける。**
> DB の制約では検出できない（既存行が後から不正になる種類の問題）ため、
> ここで確実に閉じる。

| 応答 | 条件 |
| --- | --- |
| `200 OK` | 登録成功。閉じた所属・部署長の件数を返す |
| `409` | 既に退職済み |
| `422` | 退職日が入社日より前 / 締め済みの月に遡る退職日 |

締め済みの月へ遡る退職は拒否する。確定済みの勤怠の承認者が変わってしまうため
（`approval` へ問い合わせて判定する）。

`DELETE /api/employees/{id}/retirement` は退職の取消。
閉じた所属・部署長を再度開く。誤登録の訂正にのみ使う。

### 3.6 `POST /api/employees/{id}/assignments`（異動）

```json
{ "departmentId": "0195c000-0000-7000-8000-000000000002", "validFrom": "2026-10-01" }
```

現在の所属の `validTo` を `validFrom` で閉じ、新しい所属を開く。1 トランザクション。

| 応答 | 条件 |
| --- | --- |
| `201 Created` | 異動登録成功 |
| `409 overlapping-period` | 指定日以降に既に別の所属がある（遡及異動） |
| `422` | 入社日より前 / 廃止済みの部署 |

**遡及異動は現時点では拒否する。**
実務では発令漏れや日付誤りが起きるため訂正手段が必要だが、
既に締めた月の承認者が変わる問題があり、締め処理の設計と併せて決める必要がある。
[要件定義書 10 章 未決事項 #6](../../01_要件定義/要件定義書.md#10-未決事項) として管理する。

### 3.7 `GET /api/employees/{id}/approver`

| クエリパラメータ | 型 | 必須 | 説明 |
| --- | --- | --- | --- |
| `month` | `YYYY-MM` | ○ | 対象月。BR-11 の基準日は月から導出する |

```json
{
  "month": "2026-04",
  "basisDate": "2026-04-01",
  "approver": {
    "id": "0195b000-0000-7000-8000-000000000002",
    "name": "佐藤 花子",
    "department": { "code": "S1", "name": "第一営業部" }
  },
  "resolvedFrom": "PARENT_NO_MANAGER",
  "path": [
    { "code": "S1A", "name": "第一営業課", "skippedBecause": "NO_MANAGER" },
    { "code": "S1",  "name": "第一営業部", "skippedBecause": null }
  ]
}
```

`resolvedFrom` は承認者がどう決まったかを示す。

| 値 | 意味 |
| --- | --- |
| `OWN_DEPARTMENT` | 所属部署の長 |
| `PARENT_NO_MANAGER` | 所属部署に長が未設定のため遡った |
| `PARENT_SELF_APPROVAL_AVOIDED` | **部署長が本人だったため遡った** |
| `PARENT_MANAGER_RETIRED` | 部署長が承認時点で退職済みのため遡った |
| `PARENT_DEPARTMENT_ABOLISHED` | 部署が廃止済みのため遡った |
| `HR_FALLBACK` | **根まで遡っても得られず、人事担当が承認者となる** |
| `NONE` | 対象月に所属が無い（`approver` は `null`） |

**遡った経路（`path`）も返す。**
「なぜこの人が承認者なのか」は運用中に必ず問い合わせが来る。
`PARENT_DEPARTMENT` のような粗い値では、長が未設定なのか本人だったのかを区別できない。

パラメータを `date` ではなく `month` にしているのは、
**BR-11 の基準日が「対象月の初日、ただし月中に所属開始があればその日」という
月に依存する規則だから**である。任意の日付を受け取ると規則を適用できない。

### 3.8 `GET /api/employees/{id}/assignments`（所属履歴）

```json
{
  "assignments": [
    { "departmentId": "...", "code": "S1",  "name": "第一営業部", "validFrom": "2025-04-01", "validTo": null },
    { "departmentId": "...", "code": "S1A", "name": "第一営業課", "validFrom": "2023-04-01", "validTo": "2025-04-01" }
  ]
}
```

**`validFrom` の降順（新しい順）** で返す。`validTo` が `null` は現在の所属を意味する。
半開区間なので、`validTo` の日には既に次の所属になっている。

### 3.9 `GET /api/departments`

部署をツリー構造で返す。**再帰 CTE で全件を 1 クエリで取得し、
アプリケーション側でツリーへ組み立てる。** 階層ごとにクエリを発行すると N+1 になる。

```json
{
  "departments": [
    { "id": "...", "code": "HQ", "name": "営業本部",
      "manager": { "id": "...", "name": "田中 一郎", "since": "2020-04-01" },
      "children": [
        { "id": "...", "code": "S1", "name": "第一営業部",
          "manager": { "id": "...", "name": "佐藤 花子", "since": "2021-04-01" },
          "children": [] }
      ] }
  ]
}
```

廃止済みの部署は既定で含めない（`includeAbolished=true` で含める）。

### 3.10 `PATCH /api/departments/{id}`

| 項目 | 更新可否 |
| --- | --- |
| `name` | ○ |
| `code` | ○（現存部署の間で一意） |
| `parentId` | ○ |
| `abolishedOn` | **×**（`POST /api/departments/{id}/abolition` で行う） |

> **親の変更は直列化する。**
> 循環検出トリガは他トランザクションの未コミットの変更を見ないため、
> 「A の親を C に」「C の親を A に」が同時に走ると循環が成立してしまう。
> `application` 層で `LOCK TABLE departments IN SHARE ROW EXCLUSIVE MODE` を取る。
> 部署の親変更は稀な操作なので、テーブルロックで十分。

### 3.11 `POST /api/departments/{id}/managerships`（部署長の設定）

```json
{ "employeeId": "0195b000-0000-7000-8000-000000000002", "validFrom": "2026-10-01" }
```

現任の期間を閉じて新しい期間を開く。期間の重複は DB の排他制約が拒否する。

| 応答 | 条件 |
| --- | --- |
| `201 Created` | 設定成功 |
| `409 overlapping-period` | 指定日以降に既に別の就任がある |
| `422` | 退職済みの社員 / 廃止済みの部署 |

**兼任を許容する。** 1 人が複数部署の長を兼ねることは制約しない（BR-11 補足）。

---

## 4. 実装上の注意

| # | 内容 |
| --- | --- |
| 1 | **DB の制約違反を握り潰さない。** `DataIntegrityViolationException` の制約名から 1.3 の `type` へ変換する。「登録に失敗しました」で終わらせない |
| 2 | レスポンスの `record` はドメインの型を直接公開しない。ドメインの構造変更が API の互換性を壊さないようにする |
| 3 | 閲覧範囲の絞り込みは `application` 層で行う。プレゼンテーション層に業務判断を置かない |
| 4 | `date` の既定値は `application` 層が `Clock` から解決する。コントローラで `LocalDate.now()` を呼ばない（AR-09） |
| 5 | 退職・異動・部署長交代は複数テーブルを更新する。`@Transactional` の範囲がユースケース全体を覆っていることをテストで確認する |

---

## 5. 未決事項

| # | 内容 | 判断の時期 |
| --- | --- | --- |
| 1 | 社員一覧にページングを導入するか | M1-a の実装時 |
| 2 | OpenAPI の定義から TypeScript の型を自動生成するか | M1-a の実装時 |
| 3 | 遡及異動を許可する場合の締め済み月との整合 | [05_申請承認と締め](../05_申請承認と締め/) |
