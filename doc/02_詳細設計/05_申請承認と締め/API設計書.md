# 申請・承認・締め API設計書

| 項目 | 内容 |
| --- | --- |
| 文書番号 | KNT-DES-503 |
| 版 | 0.1 |
| 対象パッケージ | `jp.co.sample.kintai.approval.presentation` |
| 関連文書 | [ドメインモデル設計書](ドメインモデル設計書.md) / [DB設計書](DB設計書.md) |

共通仕様は [社員・組織 API設計書 1 章](../01_社員・組織/API設計書.md#1-共通仕様) に従う。

---

## 1. エンドポイント一覧

| メソッド | パス | 概要 | 必要ロール |
| --- | --- | --- | --- |
| `GET` | `/api/employees/{id}/monthly-attendances/{month}` | 月次勤怠の状態 | 本人 / 承認者 / `HR` |
| `POST` | `/api/employees/{id}/monthly-attendances/{month}/submission` | 提出 | 本人 |
| `POST` | `/api/employees/{id}/monthly-attendances/{month}/approval` | 承認 | BR-11 の承認者 |
| `POST` | `/api/employees/{id}/monthly-attendances/{month}/rejection` | 差戻し | BR-11 の承認者 |
| `POST` | `/api/employees/{id}/monthly-attendances/{month}/closure` | 締め | `HR` |
| `DELETE` | `/api/employees/{id}/monthly-attendances/{month}/approval` | 承認の取消 | `HR` |
| `GET` | `/api/monthly-attendances/pending` | 承認待ちの一覧 | 承認者 / `HR` |
| `POST` | `/api/monthly-attendances/bulk-closure` | 一括締め | `HR` |
| `POST` | `/api/employees/{id}/correction-requests` | 打刻訂正の申請 | 本人 |
| `GET` | `/api/employees/{id}/correction-requests` | 訂正申請の一覧 | 本人 / 承認者 / `HR` |
| `POST` | `/api/correction-requests/{id}/approval` | 訂正の承認 | 承認者 |
| `POST` | `/api/correction-requests/{id}/rejection` | 訂正の却下 | 承認者 |

**状態遷移を「サブリソースの生成」として表現する。**
`PATCH /monthly-attendances/{month}` で `status` を直接書き換える形にすると、
どの遷移が許されるかが URL から読み取れず、権限もエンドポイント単位で分けられない。

---

## 2. 月次勤怠

### 2.1 `GET /api/employees/{id}/monthly-attendances/{month}`

```json
{
  "month": "2026-04",
  "status": "SUBMITTED",
  "submittedAt": "2026-05-01T10:00:00",
  "approvedBy": null,
  "approvedAt": null,
  "closedBy": null,
  "closedAt": null,
  "acceptsChanges": true,
  "approver": {
    "kind": "INDIVIDUAL",
    "employee": { "id": "...", "name": "佐藤 花子" },
    "resolvedFrom": "PARENT_NO_MANAGER"
  },
  "canSubmit": false,
  "canApprove": false,
  "history": [
    { "fromStatus": "DRAFT", "toStatus": "SUBMITTED",
      "actor": "山田 太郎", "comment": null, "occurredAt": "2026-05-01T10:00:00" }
  ]
}
```

| 項目 | 用途 |
| --- | --- |
| `acceptsChanges` | 打刻画面が「この月はもう打刻できない」を判断する |
| `canSubmit` / `canApprove` | **ログイン中の利用者が実行できるか** をサーバが判断して返す |
| `approver` | 誰が承認するかを本人にも示す。問い合わせを減らす |
| `history` | 差戻しの理由を含む全遷移。「なぜ戻されたか」を本人が見る |

**`canSubmit` / `canApprove` をサーバが返す。**
状態機械と BR-11 はドメインの知識であり、フロントエンドに複製すると
仕様変更のたびに 2 か所を直すことになる。

### 2.2 `POST .../submission`（提出）

リクエストボディは空。

| 応答 | 条件 |
| --- | --- |
| `200 OK` | 提出成功。更新後の状態を返す |
| `409 invalid-transition` | 下書き以外から提出しようとした |
| `409 daily-attendance-incomplete` | **未確定の勤務日が残っている** |
| `403` | 本人以外 |

```json
{
  "type": "urn:kintai:error:daily-attendance-incomplete",
  "title": "日次勤怠が確定していない日があります",
  "status": 409,
  "detail": "2026-04-12, 2026-04-20 の勤怠が確定していません",
  "incompleteDates": ["2026-04-12", "2026-04-20"]
}
```

**提出時に月次清算を実行する。** 提出された内容が承認の対象になるため、
提出の時点で確定した値を作る（[月次清算 API設計書 3.1](../04_勤怠_月次清算/API設計書.md)）。

### 2.3 `POST .../approval`（承認）

| 応答 | 条件 |
| --- | --- |
| `200 OK` | 承認成功 |
| `409 invalid-transition` | 提出済み以外から承認しようとした |
| `403 not-approver` | **実行者が BR-11 の承認者でない** |
| `409 optimistic-lock-failure` | 他の利用者が先に更新した |

```json
{
  "type": "urn:kintai:error:not-approver",
  "title": "この勤怠の承認者ではありません",
  "status": 403,
  "detail": "2026-04 の承認者は 佐藤 花子 です",
  "expectedApprover": { "id": "...", "name": "佐藤 花子" }
}
```

**期待される承認者を返す。** 承認者が想定と違う場合に、
どこへ問い合わせればよいかを利用者が判断できるようにする。

### 2.4 `POST .../rejection`（差戻し）

```json
{ "comment": "4/12 の退勤打刻が実態と異なるようです。確認してください" }
```

`comment` は **必須**（BR-10）。空なら `400`。

差戻すと下書きに戻り、打刻と訂正申請を再び受け付ける。

### 2.5 `POST .../closure`（締め）／ `DELETE .../approval`（承認の取消）

| 操作 | ロール | 備考 |
| --- | --- | --- |
| 締め | `HR` | 承認済みからのみ。**締め済みからは戻せない** |
| 承認の取消 | `HR` | 承認済み → 下書き。`comment` 必須 |

締め済みからの遷移を行う API は **用意しない。**
確定した月が戻らないことを、API の形として保証する。

> やむを得ず締めた月を訂正する必要が生じた場合の手段は、
> 監査ログ付きの特権操作として M1-c で別途検討する（未決事項 #2）。

### 2.6 `GET /api/monthly-attendances/pending`（承認待ち一覧）

| クエリパラメータ | 型 | 既定 | 説明 |
| --- | --- | --- | --- |
| `month` | `YYYY-MM` | — | 対象月で絞る |

```json
{
  "pending": [
    { "employee": { "id": "...", "employeeNumber": "E0001", "name": "山田 太郎",
                    "department": { "code": "S1A", "name": "第一営業課" } },
      "month": "2026-04",
      "submittedAt": "2026-05-01T10:00:00",
      "overtimeMinutes": 1020,
      "exceedsAgreementLimit": false }
  ]
}
```

**時間外労働と 36 協定の超過フラグを一覧に含める。**
承認者は「長時間労働になっていないか」を見て承認するため、
1 件ずつ開かないと分からない状態にしない。

### 2.7 `POST /api/monthly-attendances/bulk-closure`（一括締め）

```json
{ "month": "2026-04", "employeeIds": null }
```

`employeeIds` が `null` なら全社員が対象。

```json
{
  "month": "2026-04",
  "closed": 97,
  "skipped": [
    { "employee": { "employeeNumber": "E0042", "name": "鈴木 一郎" },
      "status": "SUBMITTED", "reason": "承認されていません" },
    { "employee": { "employeeNumber": "E0088", "name": "高橋 次郎" },
      "status": "DRAFT", "reason": "提出されていません" }
  ]
}
```

**1 人でも締められない社員がいても、全体を失敗させない。**
100 人のうち 1 人が未承認なだけで 99 人の締めが止まると運用が回らない。
締められなかった社員と理由を返し、人事が個別に対応する。

---

## 3. 打刻の訂正申請

### 3.1 `POST /api/employees/{id}/correction-requests`

```json
{
  "workDate": "2026-04-06",
  "reason": "退勤打刻を押し忘れ、翌朝に気づきました",
  "items": [
    { "action": "REVOKE", "targetEventId": "0195d000-0000-7000-8000-000000000002" },
    { "action": "ADD", "eventType": "CLOCK_OUT", "occurredAt": "2026-04-06T19:00:00" }
  ]
}
```

**「変更」という操作を用意しない。** 取消と追加の組み合わせで表現する。
変更を許すと元の打刻の値が失われ、BR-09 の目的（一次証拠の保全）を満たせない。

| 応答 | 条件 |
| --- | --- |
| `201 Created` | 申請成功 |
| `400` | 理由が空 / 項目が空 |
| `409 month-already-closed` | 締め済み・承認済みの月 |
| `409 pending-request-exists` | **同一勤務日に未処理の申請がある** |
| `422 invalid-time-clock-sequence` | **訂正を適用すると打刻列が壊れる** |

```json
{
  "type": "urn:kintai:error:invalid-time-clock-sequence",
  "title": "この訂正では打刻の順序が不正になります",
  "status": 422,
  "detail": "退勤 の打刻(2026-04-06T19:00) は 休憩中 の状態では行えません"
}
```

**申請の時点で検証する。** 承認者が承認した後で「その訂正を適用すると壊れる」と
分かるのでは遅い。申請者に修正させる。

### 3.2 `POST /api/correction-requests/{id}/approval`（訂正の承認）

承認すると 4 つの処理が 1 トランザクションで実行される。

| # | 処理 |
| --- | --- |
| 1 | 取消行を追記（`REVOCATION`） |
| 2 | 新しい打刻を追記（`ENTRY`） |
| 3 | 日次勤怠を再計算 |
| 4 | **月次勤怠を下書きへ戻す** |

```json
{
  "requestId": "...",
  "status": "APPROVED",
  "workDate": "2026-04-06",
  "attendance": { /* 再計算後の日次勤怠 */ },
  "monthlyAttendanceStatus": "DRAFT"
}
```

**4 の結果を応答に含める。** 提出済みだった月が下書きに戻ることを
承認者と申請者に伝えないと、再提出が忘れられる。

| 応答 | 条件 |
| --- | --- |
| `200 OK` | 承認成功 |
| `403 not-approver` | 実行者が承認者でない |
| `409` | 既に処理済み / 対象月が締め済み |

### 3.3 `POST /api/correction-requests/{id}/rejection`（却下）

```json
{ "comment": "打刻機の記録と一致しません。総務に確認してください" }
```

`comment` は必須。

---

## 4. 実装上の注意

| # | 内容 |
| --- | --- |
| 1 | 状態遷移はドメインの `sealed interface` に対する `switch` で行う。遷移を追加したときにコンパイルエラーで気づけるようにする |
| 2 | **締め状態の問い合わせはポート経由で受ける。** `attendance` や `workrule` が `approval` を直接参照するとコンテキストの依存に循環が生じる（[ドメインモデル設計書 5.1](ドメインモデル設計書.md)） |
| 3 | 訂正の承認は `attendance` の 3 つの更新と `approval` の 1 つの更新を含む。トランザクション境界がこれら全体を覆っていることをテストで確認する |
| 4 | `occurred_at` は `Clock` から取得する。DB の `now()` を使わない |
| 5 | 一括締めは 1 社員ずつ独立したトランザクションで処理する。全体を 1 トランザクションにすると、1 件の失敗で全件が巻き戻る |

---

## 5. 未決事項

| # | 内容 | 判断の時期 |
| --- | --- | --- |
| 1 | 提出・承認・差戻しの通知（メール等） | M2 |
| 2 | 締め済みの月を訂正する特権操作 | M1-c の実装時 |
| 3 | 承認者が長期不在の場合の代理承認 | M2 |
