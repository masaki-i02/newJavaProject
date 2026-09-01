# 社員・組織 API設計書

| 項目 | 内容 |
| --- | --- |
| 文書番号 | KNT-DES-103 |
| 版 | 0.1 |
| 対象パッケージ | `jp.co.sample.kintai.employee.presentation` |
| 関連文書 | [ドメインモデル設計書](ドメインモデル設計書.md) / [DB設計書](DB設計書.md) |

---

## 1. 共通仕様

| 項目 | 仕様 |
| --- | --- |
| ベースパス | `/api` |
| 形式 | JSON（UTF-8） |
| 日付 | ISO 8601（`2026-04-01`） |
| 日時 | ISO 8601（`2026-04-01T09:00:00`）。**会社基準タイムゾーンの壁掛け時計時刻**とし、オフセットを含めない |
| 識別子 | UUID の文字列表現 |
| エラー | RFC 9457 (Problem Details) |
| 認証 | セッション（`JSESSIONID`）。未認証は 401 |

### 1.1 日時にオフセットを含めない理由

打刻や始業時刻は「会社の壁掛け時計が何時を指していたか」であり、
クライアントのタイムゾーンで解釈されるべきではない。オフセット付きで返すと、
ブラウザのタイムゾーン設定によって表示がずれる。
タイムゾーンはサーバ側で `Asia/Tokyo` に固定する（アーキテクチャ設計書 6.3）。

### 1.2 エラー応答

```json
{
  "type": "urn:kintai:error:resource-not-found",
  "title": "対象が見つかりません",
  "status": 404,
  "detail": "社員が存在しません: 0195b000-0000-7000-8000-000000000001",
  "instance": "/api/employees/0195b000-0000-7000-8000-000000000001"
}
```

| `type` | HTTP | 発生条件 |
| --- | --- | --- |
| `urn:kintai:error:resource-not-found` | 404 | 指定した社員・部署が存在しない |
| `urn:kintai:error:validation-failed` | 400 | 入力値が不正（項目ごとの詳細を `errors` に含める） |
| `urn:kintai:error:conflict` | 409 | 一意制約違反、期間の重複、部署階層の循環 |
| `urn:kintai:error:forbidden` | 403 | ロールまたは閲覧範囲の不足 |

---

## 2. エンドポイント一覧

| メソッド | パス | 概要 | 必要ロール |
| --- | --- | --- | --- |
| `GET` | `/api/me` | ログイン中の社員の情報とロール | 認証のみ |
| `GET` | `/api/employees` | 社員一覧 | `EMPLOYEE`（範囲はロールに依存） |
| `GET` | `/api/employees/{id}` | 社員の詳細 | `EMPLOYEE`（範囲はロールに依存） |
| `POST` | `/api/employees` | 社員の登録 | `ADMIN` |
| `PATCH` | `/api/employees/{id}` | 社員情報の更新 | `ADMIN` |
| `POST` | `/api/employees/{id}/retirement` | 退職の登録 | `ADMIN` |
| `GET` | `/api/employees/{id}/assignments` | 所属履歴 | `EMPLOYEE`（範囲はロールに依存） |
| `POST` | `/api/employees/{id}/assignments` | 異動の登録 | `ADMIN` |
| `GET` | `/api/employees/{id}/approver` | 指定日の承認者 | `EMPLOYEE`（本人または `HR`） |
| `GET` | `/api/departments` | 部署ツリー | `EMPLOYEE` |
| `POST` | `/api/departments` | 部署の登録 | `ADMIN` |
| `PATCH` | `/api/departments/{id}` | 部署の更新（親の変更を含む） | `ADMIN` |
| `POST` | `/api/departments/{id}/managerships` | 部署長の設定・交代 | `ADMIN` |

### 2.1 閲覧範囲（ロールによる差）

`GET /api/employees` が返す範囲は、ロールによって変わる。

| ロール | 範囲 |
| --- | --- |
| `EMPLOYEE` のみ | 自分自身のみ |
| `APPROVER` | 自分 + 自分が長を務める部署の配下すべて（再帰的） |
| `HR` / `ADMIN` | 全社員 |

**この判定は Spring Security のロール判定だけでは表現できない。**
「自分が長を務める部署の配下か」は組織の状態に依存する業務判断であり、
`employee` コンテキストのドメインサービスが担う（アーキテクチャ設計書 6.4）。

---

## 3. 主要なエンドポイントの詳細

### 3.1 `GET /api/me`

ログイン直後にフロントエンドが呼び、画面の出し分けに使う。

```json
{
  "id": "0195b000-0000-7000-8000-000000000001",
  "employeeNumber": "E0001",
  "name": "山田 太郎",
  "employmentType": "FULL_TIME",
  "roles": ["EMPLOYEE"],
  "department": {
    "id": "0195c000-0000-7000-8000-000000000003",
    "code": "S1A",
    "name": "第一営業課"
  },
  "workingTimeSystem": "FIXED"
}
```

`workingTimeSystem` を含めるのは、**打刻画面の表示が勤務形態で変わる**ためである
（フレックスでは日々の残業を表示せず、月次の清算状況を出す）。

### 3.2 `GET /api/employees`

| クエリパラメータ | 型 | 既定 | 説明 |
| --- | --- | --- | --- |
| `date` | `date` | 当日 | この日付時点の所属で絞る |
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
      "employmentType": "FULL_TIME",
      "hiredOn": "2023-04-01",
      "retiredOn": null,
      "department": { "id": "...", "code": "S1A", "name": "第一営業課" }
    }
  ]
}
```

配列を裸で返さずオブジェクトで包むのは、**将来ページングのメタ情報を追加できるようにする**ため。
一度 `[...]` で公開すると、後から `{ "employees": [...], "total": 100 }` へ変えられない。

### 3.3 `POST /api/employees`

```json
{
  "employeeNumber": "E0004",
  "name": "鈴木 一郎",
  "email": "suzuki@example.com",
  "employmentType": "FULL_TIME",
  "hiredOn": "2026-10-01",
  "departmentId": "0195c000-0000-7000-8000-000000000003",
  "roles": ["EMPLOYEE"]
}
```

**社員番号は手入力とする。** 人事は既存の採番体系（部門記号を含むなど）を持っていることが多く、
システムが自動採番すると実態と合わなくなるため。一意性は DB の制約が保証する。

社員の登録と同時に、`hiredOn` を開始日とする所属（`assignments`）を 1 件作成する。
**所属のない社員を作れないようにする。** 所属が無いと承認者が決まらず、勤怠を提出できないため。

| 応答 | 条件 |
| --- | --- |
| `201 Created` | 登録成功。`Location` ヘッダに `/api/employees/{id}` |
| `400` | 入力値が不正 |
| `409` | 社員番号またはメールアドレスが重複 |

### 3.4 `POST /api/employees/{id}/assignments`（異動）

```json
{
  "departmentId": "0195c000-0000-7000-8000-000000000002",
  "validFrom": "2026-10-01"
}
```

**現在の所属の `validTo` を `validFrom` で閉じ、新しい所属を開く** という 2 つの更新を
1 トランザクションで行う。期間が重なれば DB の排他制約が拒否するため、
アプリケーション側で重複チェックを書かない。

| 応答 | 条件 |
| --- | --- |
| `201 Created` | 異動登録成功 |
| `409` | 指定日以降に既に別の所属がある（過去に遡る異動など） |

### 3.5 `GET /api/employees/{id}/approver`

| クエリパラメータ | 型 | 既定 | 説明 |
| --- | --- | --- | --- |
| `date` | `date` | 当日 | この日付時点の承認者を求める |

```json
{
  "date": "2026-04-01",
  "approver": {
    "id": "0195b000-0000-7000-8000-000000000002",
    "name": "佐藤 花子",
    "department": { "code": "S1", "name": "第一営業部" }
  },
  "resolvedFrom": "PARENT_DEPARTMENT"
}
```

`resolvedFrom` は承認者がどう決まったかを示す。

| 値 | 意味 |
| --- | --- |
| `OWN_DEPARTMENT` | 所属部署の長 |
| `PARENT_DEPARTMENT` | 所属部署に長がいないため親を辿った |
| `NONE` | 根まで遡っても見つからなかった（`approver` は `null`） |

**導出の経緯を返すのは、承認者が想定と違うときに原因を追えるようにするため。**
「なぜこの人が承認者なのか」は運用中に必ず問い合わせが来る。

### 3.6 `GET /api/departments`

部署をツリー構造で返す。

```json
{
  "departments": [
    {
      "id": "...", "code": "HQ", "name": "営業本部",
      "manager": { "id": "...", "name": "田中 一郎" },
      "children": [
        {
          "id": "...", "code": "S1", "name": "第一営業部",
          "manager": { "id": "...", "name": "佐藤 花子" },
          "children": [
            { "id": "...", "code": "S1A", "name": "第一営業課",
              "manager": null, "children": [] }
          ]
        }
      ]
    }
  ]
}
```

**再帰 CTE で全件を 1 クエリで取得し、アプリケーション側でツリーへ組み立てる。**
階層ごとにクエリを発行すると N+1 になるため。

### 3.7 `POST /api/departments/{id}/managerships`（部署長の設定）

```json
{
  "employeeId": "0195b000-0000-7000-8000-000000000002",
  "validFrom": "2026-10-01"
}
```

異動と同様、現任の期間を閉じて新しい期間を開く。
期間の重複は DB の排他制約が拒否する。

---

## 4. 実装上の注意

| # | 内容 |
| --- | --- |
| 1 | **DB の制約違反を握り潰さない。** `DataIntegrityViolationException` を捕捉し、制約名から業務的な意味に変換して 409 で返す。「登録に失敗しました」で終わらせない |
| 2 | レスポンスの `record` はドメインの型を直接公開しない。ドメインの構造変更が API の互換性を壊さないようにする |
| 3 | `GET /api/employees` の閲覧範囲の絞り込みは、コントローラではなく `application` 層で行う。プレゼンテーション層に業務判断を置かない |

---

## 5. 未決事項

| # | 内容 | 判断の時期 |
| --- | --- | --- |
| 1 | 社員の一覧にページングを導入するか（100 名なら不要だが将来のため） | M1-a の実装時 |
| 2 | OpenAPI の定義から TypeScript の型を自動生成するか | M1-a の実装時 |
