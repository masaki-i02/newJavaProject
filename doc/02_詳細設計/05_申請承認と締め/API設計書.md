# 申請・承認・締め API設計書

| 項目 | 内容 |
| --- | --- |
| 文書番号 | KNT-DES-503 |
| 版 | 0.2 |
| 対象パッケージ | `jp.co.sample.kintai.approval.presentation` |
| 関連文書 | [ドメインモデル設計書](ドメインモデル設計書.md) / [DB設計書](DB設計書.md) / [設計規約チェックリスト](../00_共通/設計規約チェックリスト.md) |
| 改訂 | 0.2（2026-09-01）設計レビュー第 2 回の指摘を反映 |

共通仕様は [社員・組織 API設計書 1 章](../01_社員・組織/API設計書.md#1-共通仕様) に従う。

---

## 1. エンドポイント一覧

| メソッド | パス | 概要 | 必要ロール |
| --- | --- | --- | --- |
| `GET` | `/api/employees/{id}/monthly-attendances/{month}` | 月次勤怠の状態 | 本人 / 承認者 / `HR` / `ADMIN` |
| `POST` | `/api/employees/{id}/monthly-attendances/{month}/submission` | 提出 | 本人、または `HR`（本人が在籍していない場合） |
| `POST` | `/api/employees/{id}/monthly-attendances/{month}/approval` | 承認 | BR-11 の承認者 |
| `POST` | `/api/employees/{id}/monthly-attendances/{month}/rejection` | 差戻し | BR-11 の承認者 |
| `POST` | `/api/employees/{id}/monthly-attendances/{month}/closure` | 締め | `HR` |
| `DELETE` | `/api/employees/{id}/monthly-attendances/{month}/approval` | 承認の取消 | `HR` |
| `GET` | `/api/monthly-attendances/pending` | 承認待ちの一覧 | 承認者 / `HR` |
| `POST` | `/api/monthly-attendances/bulk-closure` | 一括締め | `HR` |
| `POST` | `/api/employees/{id}/correction-requests` | 打刻訂正の申請 | 本人 |
| `GET` | `/api/employees/{id}/correction-requests` | 訂正申請の一覧 | 本人 / 承認者 / `HR` / `ADMIN` |
| `POST` | `/api/correction-requests/{id}/approval` | 訂正の承認 | 承認者 |
| `POST` | `/api/correction-requests/{id}/rejection` | 訂正の却下 | 承認者 |
| `POST` | `/api/correction-requests/{id}/cancellation` | **訂正の取下げ** | 本人 |

### 1.1 楽観ロック

状態を変える操作は、[共通仕様 1.4](../01_社員・組織/API設計書.md) に従って
リクエストボディに `version` を必須で含める。

| エンドポイント | `version` | 対象 |
| --- | --- | --- |
| 提出・承認・差戻し・締め・承認取消 | ○ | `monthly_attendances.version` |
| 訂正の承認・却下・取下げ | ○ | `time_clock_correction_requests.version` |
| **一括締め** | **×** | 対象が複数のため。同時実行は社員ごとに検出し、失敗として結果に含める |

不一致なら `409 urn:kintai:error:optimistic-lock-failure` を返す。
`GET` の応答には必ず `version` を含める。**取得する経路が無いと送れない。**

### 1.2 このコンテキストのエラー型

| `type` | HTTP | 発生条件 |
| --- | --- | --- |
| `urn:kintai:error:optimistic-lock-failure` | 409 | `version` が一致しない |
| `urn:kintai:error:invalid-transition` | 409 | 現在の状態から実行できない遷移 |
| `urn:kintai:error:month-not-finished` | 409 | 対象月の末日が到来していない |
| `urn:kintai:error:daily-attendance-incomplete` | 409 | 未確定の日次勤怠が残っている |
| `urn:kintai:error:month-already-closed` | 409 | 締め済みの月 |
| `urn:kintai:error:month-not-editable` | 409 | 承認済みまたは締め済みで変更できない |
| `urn:kintai:error:not-approver` | 403 | BR-11 の承認者ではない |
| `urn:kintai:error:pending-correction-exists` | 409 | 同じ勤務日に未処理の訂正申請がある |

**状態遷移を「サブリソースの生成」として表現する。**
`PATCH /monthly-attendances/{month}` で `status` を直接書き換える形にすると、
どの遷移が許されるかが URL から読み取れず、権限もエンドポイント単位で分けられない。

---

## 2. 月次勤怠

### 2.1 `GET /api/employees/{id}/monthly-attendances/{month}`

```json
{
  "month": "2026-04",
  "version": 3,
  "status": "SUBMITTED",
  "submittedAt": "2026-05-01T10:00:00",
  "submittedBy": "0195c000-0000-7000-8000-000000000001",
  "approvedBy": null,
  "approvedAt": null,
  "closedBy": null,
  "closedAt": null,
  "acceptsTimeClock": false,
  "acceptsCorrectionRequest": true,
  "approver": {
    "kind": "INDIVIDUAL",
    "employeeId": "0195c000-0000-7000-8000-000000000002",
    "path": [
      { "departmentId": "...", "reason": "NO_MANAGER" },
      { "departmentId": "...", "reason": "NONE" }
    ]
  },
  "canSubmit": false,
  "canApprove": false,
  "history": [
    { "eventKind": "SUBMIT", "fromStatus": "DRAFT", "toStatus": "SUBMITTED",
      "actorId": "0195c000-0000-7000-8000-000000000001",
      "comment": null, "occurredAt": "2026-05-01T10:00:00" }
  ]
}
```

| 項目 | 用途 |
| --- | --- |
| `version` | 更新系のリクエストで必須（1.1）。**取得する経路がここしかない** |
| `acceptsTimeClock` | 打刻画面が「この月はもう打刻できない」を判断する |
| `acceptsCorrectionRequest` | 訂正申請の画面が同じ判断をする。**提出済では打刻は不可、訂正申請は可** |
| `canSubmit` / `canApprove` | **ログイン中の利用者が実行できるか** をサーバが判断して返す |
| `approver` | 誰が承認するかを本人にも示す。問い合わせを減らす |
| `history` | 差戻しの理由を含む全遷移。「なぜ戻されたか」を本人が見る |

**`acceptsChanges` を 2 つに分けた。**
第 1 版は 1 つの値で打刻と訂正申請の両方を表しており、
提出済みが `true` を返すので **本人が提出後に直接打刻できてしまった**
（[ドメインモデル設計書 2.1](ドメインモデル設計書.md)）。

**氏名・社員番号・部署名を返さない。** それらは `employee` コンテキストが所有する概念であり、
`approval` の応答に混ぜない（[設計規約チェックリスト 3](../00_共通/設計規約チェックリスト.md)）。
画面は `GET /api/employees?ids=...` でまとめて引く。

`history` の `eventKind` は、同じ `SUBMITTED → DRAFT` でも
**差戻し（`REJECT`）と訂正承認による自動差戻し（`REVERT_BY_CORRECTION`）を区別する。**
本人に非があるかどうかが違うため。

**`canSubmit` / `canApprove` をサーバが返す。**
状態機械と BR-11 はドメインの知識であり、フロントエンドに複製すると
仕様変更のたびに 2 か所を直すことになる。

### 2.2 `POST .../submission`（提出）

```json
{ "version": 2 }
```

代理提出の場合は理由を添える。

```json
{ "version": 2, "comment": "退職者（最終在籍日 2026-03-31）の代理提出" }
```

| 応答 | 条件 |
| --- | --- |
| `200 OK` | 提出成功。更新後の状態を返す |
| `409 invalid-transition` | 下書き以外から提出しようとした |
| `409 optimistic-lock-failure` | `version` が一致しない |
| `409 month-not-finished` | **対象月の末日が到来していない** |
| `409 daily-attendance-incomplete` | **未確定の勤務日が残っている** |
| `403 forbidden` | 本人でなく、`HR` でもない／本人が在籍しているのに `HR` が代理提出しようとした |
| `422 business-rule-violation` | 代理提出なのに理由が空 |

#### 実行者

| 状況 | 実行できる人 |
| --- | --- |
| 本人が在籍している | **本人のみ** |
| 提出時点で本人が在籍していない（退職済み） | **`HR`（代理提出）。理由が必須** |

退職者の最終月は本人がログインできない。代理提出を認めないと
**提出済に到達できず、承認も締めもできない**
（[ドメインモデル設計書 2.4](ドメインモデル設計書.md)）。

#### 対象月の末日が到来していること

月初に提出しようとすると、勤務日がまだ来ていないので
未確定リストが空になり、そのまま承認・締めまで通ってしまう。
締めると戻せないので、**末日の到来を事前条件にする。**

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
| `409 optimistic-lock-failure` | `version` が一致しない |

リクエストボディに `version` を含める。

```json
{ "version": 3 }
```

```json
{
  "type": "urn:kintai:error:not-approver",
  "title": "この勤怠の承認者ではありません",
  "status": 403,
  "detail": "2026-04 の承認者は別の社員です",
  "expectedApproverId": "0195c000-0000-7000-8000-000000000002"
}
```

**期待される承認者の ID を返す。** 承認者が想定と違う場合に、
どこへ問い合わせればよいかを利用者が判断できるようにする。
氏名は `employee` から引く。

### 2.4 `POST .../rejection`（差戻し）

```json
{ "version": 3, "comment": "4/12 の退勤打刻が実態と異なるようです。確認してください" }
```

`comment` は **必須**（BR-10）。空文字と空白のみも許さない。空なら `400`。

差戻すと下書きに戻り、打刻と訂正申請を再び受け付ける。

### 2.5 `POST .../closure`（締め）／ `DELETE .../approval`（承認の取消）

| 操作 | ロール | 備考 |
| --- | --- | --- |
| 締め | `HR` | 承認済みからのみ。対象月の末日が到来していること。**締め済みからは戻せない** |
| 承認の取消 | `HR` | 承認済み → 下書き。`comment` 必須 |

どちらもリクエストボディに `version` を含める。

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
    { "employeeId": "0195c000-0000-7000-8000-000000000001",
      "month": "2026-04",
      "version": 3,
      "submittedAt": "2026-05-01T10:00:00",
      "proxySubmitted": false,
      "overtimeMinutes": 1020,
      "exceedsAgreementLimit": false,
      "breakShortageDays": ["2026-04-12"] }
  ]
}
```

**時間外労働と 36 協定の超過フラグを一覧に含める。**
承認者は「長時間労働になっていないか」を見て承認するため、
1 件ずつ開かないと分からない状態にしない。

**休憩不足の日（BR-08）も含める。** BR-08 は「不足していても計算は打刻どおりに行い、
警告を出すだけ」と定めているので提出は妨げないが、
**承認者が見るべき警告**である。一覧に出さないと誰も気づかない。

`proxySubmitted` は `HR` による代理提出かどうか。
承認者にとって「本人が確認していない内容」であることは判断材料になる。

社員番号・氏名・部署は返さない。画面が `employee` から引く。

### 2.7 `POST /api/monthly-attendances/bulk-closure`（一括締め）

```json
{ "month": "2026-04", "employeeIds": null }
```

`employeeIds` が `null` なら全社員が対象。

```json
{
  "month": "2026-04",
  "closed": 96,
  "skipped": [
    { "employeeId": "0195c000-0000-7000-8000-000000000042",
      "status": "SUBMITTED", "reason": "承認されていません" },
    { "employeeId": "0195c000-0000-7000-8000-000000000088",
      "status": "DRAFT", "reason": "提出されていません" },
    { "employeeId": "0195c000-0000-7000-8000-000000000099",
      "status": "APPROVED", "reason": "他の利用者が先に更新しました" }
  ]
}
```

**リクエストに `version` を取らない。** 対象が複数だからである。
同時実行は社員ごとの楽観ロックで検出し、
`skipped` の 1 件として理由つきで返す（1.1）。

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
| `409 month-not-editable` | **承認済みの月**（まだ締めてはいない） |
| `409 month-already-closed` | 締め済みの月 |
| `409 pending-correction-exists` | **同一勤務日に未処理の申請がある** |
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

**`month-already-closed` と `month-not-editable` を分ける。**
承認済みは締め済みではない。承認を取り消せば直せるので、
利用者への案内がまったく違う。粗い型にまとめない
（[共通仕様 1.3](../01_社員・組織/API設計書.md#13-エラー応答)）。

`REVOKE` には `workDate` を暗黙に申請の `workDate` から取る。
`time_clock_events` は `work_date` でパーティション分割されているため、
`id` だけでは引けない（[日次集計 DB設計書 4.1](../03_勤怠_打刻と日次集計/DB設計書.md)）。

**打刻漏れの補完は `ADD` だけの申請でよい。** 取り消す対象が存在しないため。

### 3.2 `POST /api/correction-requests/{id}/approval`（訂正の承認）

```json
{ "version": 1 }
```

承認すると 5 つの処理が 1 トランザクションで実行される。

| # | 処理 | 対象 |
| --- | --- | --- |
| 1 | 取消行を追記（`REVOCATION`） | `attendance` |
| 2 | 新しい打刻を追記（`ENTRY`。`source = CORRECTION`・理由つき） | `attendance` |
| 3 | 日次勤怠を再計算 | `attendance` |
| 4 | **月次清算を再計算** | `attendance`（月次清算） |
| 5 | **月次勤怠を下書きへ戻す**（`RevertByCorrection`） | `approval` |

```json
{
  "requestId": "...",
  "status": "APPROVED",
  "workDate": "2026-04-06",
  "attendance": { /* 再計算後の日次勤怠 */ },
  "settlement": { /* 再計算後の月次清算 */ },
  "monthlyAttendanceStatus": "DRAFT"
}
```

**4 と 5 の結果を応答に含める。** 提出済みだった月が下書きに戻ることを
承認者と申請者に伝えないと、再提出が忘れられる。
月次清算を再計算しないと、日次だけが直って月次の時間外が古いままになる。

**承認者は `workDate` が属する月の BR-11 の承認者である。**
月次勤怠と訂正で承認者が食い違わないようにする
（[ドメインモデル設計書 4.1.2](ドメインモデル設計書.md)）。

| 応答 | 条件 |
| --- | --- |
| `200 OK` | 承認成功 |
| `403 not-approver` | 実行者が `workDate` の月の承認者でない |
| `409 invalid-transition` | 既に処理済み（承認・却下・取下げ済み） |
| `409 optimistic-lock-failure` | `version` が一致しない |
| `409 month-already-closed` | 対象月が締め済み |

### 3.3 `POST /api/correction-requests/{id}/rejection`（却下）

```json
{ "version": 1, "comment": "打刻機の記録と一致しません。総務に確認してください" }
```

`comment` は必須。空文字と空白のみも許さない。

### 3.4 `POST /api/correction-requests/{id}/cancellation`（取下げ）

```json
{ "version": 1 }
```

**本人が自分の申請を取り下げる。**

| 応答 | 条件 |
| --- | --- |
| `200 OK` | 取下げ成功。状態が `CANCELED` になる |
| `403 forbidden` | 本人以外 |
| `409 invalid-transition` | 既に処理済み |
| `409 optimistic-lock-failure` | `version` が一致しない |

取下げが無いと、誤って申請した本人は
**承認者が却下するまで正しい申請を出し直せない**
（同一勤務日の未処理の申請は 1 件までという制約があるため）。
承認者が不在なら、その勤務日の訂正が滞留する。

`CANCELED` と `REJECTED` を分けるのは、**却下は承認者の判断、取下げは本人の意思**であり、
証跡で区別できないと「何度も却下されている社員」という誤読が生まれるためである。

---

## 4. 実装上の注意

| # | 内容 |
| --- | --- |
| 1 | 状態遷移はドメインの `sealed interface` に対する `switch` で行う。遷移を追加したときにコンパイルエラーで気づけるようにする |
| 2 | **締め状態の問い合わせは `shared.domain.MonthClosureQuery` を通じて受ける。** 実装（`MonthClosureQueryAdapter`）を提供するのが本コンテキストの責務。ポートを `attendance/domain` に置くと `workrule → attendance` という依存図に無い辺が必要になる（[ドメインモデル設計書 5.1](ドメインモデル設計書.md)） |
| 3 | 訂正の承認は `attendance` の 4 つの更新と `approval` の 1 つの更新を含む。トランザクション境界がこれら全体を覆っていることをテストで確認する |
| 4 | `occurred_at` は `Clock` から取得する。DB の `now()` を使わない |
| 5 | 一括締めは 1 社員ずつ独立したトランザクションで処理する。全体を 1 トランザクションにすると、1 件の失敗で全件が巻き戻る。`closeAll` に `@Transactional` を付けない |
| 6 | `monthly_attendances` の行は **提出時に初めて作る。** 打刻のたびに作ると `attendance → approval` の書き込み依存が生まれる。行が無い月は下書き相当として扱う |
| 7 | 提出・締めの事前条件に「対象月の末日が到来していること」を含める。判定は `Clock` から（AR-09） |

---

## 4.1 API の結合テストの観点

API から DB までを通す。**コントローラだけを切り出してリポジトリを差し替えない。**
層をまたいだ欠陥（版の初期値・状態と列の対応・閲覧範囲）を素通りさせるため。

| ID | 観点 | 期待 | 対応要件 |
| --- | --- | --- | --- |
| IT-APV-30 | **提出 → 承認 → 締め まで通る** | 各段で 200。最後は `CLOSED` | BR-10 |
| IT-APV-31 | 遷移が監査証跡に残る | `SUBMIT` / `APPROVE` / `CLOSE` が順に記録される | 要件 7 章 |
| IT-APV-32 | 締め済みの月への遷移 | 409 `invalid-attendance-transition` | BR-10 |
| IT-APV-33 | **締め済みの月は承認の取消もできない** | 409。確定値は動かない | BR-10 |
| IT-APV-34 | 下書きの月を承認 | 409 | BR-10 |
| IT-APV-35 | 二重提出 | 409 | BR-10 |
| IT-APV-36 | **本人が自分の勤怠を承認** | 403 `self-approval` | BR-11 の 4 |
| IT-APV-37 | 承認者でない社員が承認 | 403 | BR-11 |
| IT-APV-38 | **個人の承認者がいる月を人事が承認** | 403。人事が承認するのは遡っても得られない場合だけ | BR-11 の 5 |
| IT-APV-39 | 人事でない利用者が締める | 403 | BR-10 |
| IT-APV-40 | **在籍中の社員の勤怠を人事が代理提出** | 403。本人が提出できるなら本人が提出する | BR-10 |
| IT-APV-41 | 差戻しの理由が空白のみ | 400 | BR-10 |
| IT-APV-42 | 差戻しが `REJECT` として証跡に残る | 理由つきで記録される | 要件 7 章 |
| IT-APV-43 | 承認の取消も理由が必須 | 空なら 400 | BR-10 |
| IT-APV-44 | **対象月の末日が到来する前に提出** | 409 `month-not-finished` | BR-10 |
| IT-APV-45 | **未計算の勤務日があると提出できない** | 409。**どの日かを応答に含める** | BR-10 |
| IT-APV-46 | 月中入社の初月を提出 | 勤務日は入社日から数え、提出できる | BR-10 / BR-11 |
| IT-APV-47 | 承認待ち一覧 | **見てよい社員のぶんだけ返る** | BR-11 |
| IT-APV-48 | **`GET` が版を返す** | 行が無ければ 0、提出後は 1 | 1.1 |
| IT-APV-49 | **古い版で承認** | 409 `optimistic-lock-failure`。状態は変わらない | 1.1 |
| IT-APV-50 | **版を含まない要求** | 400。既定値の 0 で通してはならない | 1.1 |
| IT-APV-51 | **一括締めに未提出の社員が混ざる** | 承認済みの社員は締まり、残りは理由つきで `skipped` へ | 2.7 |
| IT-APV-52 | 一括締めで提出済のままの社員 | `skipped` に「承認されていません」 | 2.7 |
| IT-APV-53 | **人事でない利用者の一括締め** | 403。全員を `skipped` にして 200 で返さない | 2.7 |
| IT-APV-54 | `employeeIds` を省く | 全社員が対象になる | 2.7 |
| IT-APV-55 | **月中に退職した社員** | 全社員が対象なら含まれる。外すとその月が永久に締まらない | 2.7 |

**IT-APV-53 は「社員ごとの事情」と「依頼そのものの不備」を分ける。**
人事でない・対象月がまだ終わっていない、は依頼が成り立たないので例外のまま返す。
全員を `skipped` に並べると、人事は「自分に権限が無い」ことに気づけない。

---

## 5. 未決事項

| # | 内容 | 判断の時期 |
| --- | --- | --- |
| 1 | 提出・承認・差戻しの通知（メール等） | M2 |
| 2 | 締め済みの月を訂正する特権操作 | M1-c の実装時 |
| 3 | 承認者が長期不在の場合の代理承認 | M2 |
