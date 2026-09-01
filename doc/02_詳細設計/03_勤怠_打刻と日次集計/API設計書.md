# 勤怠（打刻・日次集計） API設計書

| 項目 | 内容 |
| --- | --- |
| 文書番号 | KNT-DES-303 |
| 版 | 0.1 |
| 対象パッケージ | `jp.co.sample.kintai.attendance.presentation` |
| 関連文書 | [ドメインモデル設計書](ドメインモデル設計書.md) / [DB設計書](DB設計書.md) |

共通仕様（形式・日時・`date` パラメータ・エラー・楽観ロック）は
[社員・組織 API設計書 1 章](../01_社員・組織/API設計書.md#1-共通仕様) に従う。

---

## 1. エンドポイント一覧

| メソッド | パス | 概要 | 必要ロール |
| --- | --- | --- | --- |
| `POST` | `/api/employees/{id}/time-clocks` | 打刻する | 本人 |
| `GET` | `/api/employees/{id}/time-clocks` | 指定日の打刻（取消済みを含む履歴） | 本人 / 上長 / `HR` |
| `GET` | `/api/employees/{id}/attendances` | 月次の日次勤怠一覧 | 本人 / 上長 / `HR` |
| `GET` | `/api/employees/{id}/attendances/{date}` | 指定日の日次勤怠（内訳つき） | 本人 / 上長 / `HR` |
| `POST` | `/api/employees/{id}/attendances/{date}/recalculation` | 日次勤怠の再計算 | `HR` |
| `GET` | `/api/employees/{id}/attendances/current` | 現在の勤務状態（打刻画面用） | 本人 |

**打刻の訂正はこのコンテキストの API に含まれない。**
訂正は申請と承認を伴うため、`approval` コンテキストが受け付ける（BR-09）。
承認された結果として、取消行と新しい打刻行がここへ追記される。

---

## 2. 打刻

### 2.1 `POST /api/employees/{id}/time-clocks`

```json
{ "type": "CLOCK_IN", "occurredAt": "2026-04-07T09:00:00", "source": "WEB" }
```

| 項目 | 必須 | 説明 |
| --- | --- | --- |
| `type` | ○ | `CLOCK_IN` / `CLOCK_OUT` / `BREAK_START` / `BREAK_END` |
| `occurredAt` | — | 省略時は `application` 層が `Clock` から解決した現在時刻 |
| `source` | — | 既定は `WEB` |

応答（勤務が完了していない場合）:

```json
{ "workDate": "2026-04-07", "status": "WORKING", "closed": false }
```

応答（退勤まで完了した場合）:

```json
{
  "workDate": "2026-04-07",
  "status": "FINISHED",
  "closed": true,
  "attendance": {
    "workDate": "2026-04-07",
    "dayType": "WORKDAY",
    "workingMinutes": 780,
    "breakMinutes": 60,
    "withinScheduledMinutes": 480,
    "overtimeWithinStatutoryMinutes": 0,
    "overtimeBeyondStatutoryMinutes": 300,
    "nightMinutes": 300,
    "legalHolidayMinutes": 0,
    "breakRequirementSatisfied": true,
    "slices": [
      { "startedAt": "2026-04-07T13:00:00", "endedAt": "2026-04-07T18:00:00",
        "minutes": 300, "premiums": [] },
      { "startedAt": "2026-04-07T19:00:00", "endedAt": "2026-04-07T22:00:00",
        "minutes": 180, "premiums": [] },
      { "startedAt": "2026-04-07T22:00:00", "endedAt": "2026-04-08T03:00:00",
        "minutes": 300, "premiums": ["NIGHT", "OVERTIME_BEYOND_STATUTORY"] }
    ]
  }
}
```

| 決定 | 理由 |
| --- | --- |
| 時間を分単位の整数で返す | ISO-8601 の `PT8H30M` は加工が面倒。小数の「時間」は丸め誤差を生む |
| 勤務日（`workDate`）を必ず返す | 日をまたぐ勤務では、打刻した日と勤務日が一致しない（BR-03）。利用者に「これは前日の勤務です」と示す必要がある |
| 内訳（`slices`）を返す | 「なぜこの残業時間か」を画面で説明できるようにする |
| 退勤前は `attendance` を返さない | 勤務が完了していない時点の集計値は意味を持たない |

#### エラー

| `type` | HTTP | 発生条件 |
| --- | --- | --- |
| `urn:kintai:error:invalid-time-clock-sequence` | 409 | 打刻の順序が状態機械に反する |
| `urn:kintai:error:unclosed-previous-work-date` | 409 | **前の勤務日が未退勤のまま出勤しようとした** |
| `urn:kintai:error:month-already-closed` | 409 | 締め済みの月への打刻 |
| `urn:kintai:error:work-rule-not-found` | 422 | その日に適用される就業規則が無い |

```json
{
  "type": "urn:kintai:error:unclosed-previous-work-date",
  "title": "前の勤務日が完了していません",
  "status": 409,
  "detail": "2026-04-06 の退勤打刻がありません。先に訂正を申請してください",
  "unclosedWorkDate": "2026-04-06"
}
```

**`unclosedWorkDate` を応答に含める。**
このエラーは運用上必ず起き、利用者は「どの日を直せばよいか」を知る必要がある。
「エラーが発生しました」で終わらせると、問い合わせが人事へ流れる。

### 2.2 `GET /api/employees/{id}/attendances/current`（現在の勤務状態）

打刻画面が「次に押せるボタン」を決めるために使う。

```json
{
  "workDate": "2026-04-07",
  "status": "ON_BREAK",
  "availableActions": ["BREAK_END"],
  "punches": [
    { "type": "CLOCK_IN",    "occurredAt": "2026-04-07T09:00:00" },
    { "type": "BREAK_START", "occurredAt": "2026-04-07T12:00:00" }
  ],
  "unclosedWorkDate": null
}
```

**`availableActions` をサーバが返す。**
状態機械（BR-02）はドメインの知識であり、フロントエンドに複製すると
仕様変更のたびに 2 か所を直すことになる。ボタンの活性制御はこの配列に従う。

`unclosedWorkDate` が非 `null` なら、画面は打刻ボタンを出さずに
訂正申請へ誘導する。

### 2.3 `GET /api/employees/{id}/time-clocks`

| クエリパラメータ | 型 | 必須 | 説明 |
| --- | --- | --- | --- |
| `workDate` | `date` | ○ | 勤務日 |

**取り消された打刻も含めて返す。** 打刻は労働時間の一次証拠であり、
「元は何時だったか」を提示できることが BR-09 の目的だから。

```json
{
  "workDate": "2026-04-07",
  "entries": [
    { "id": "...", "type": "CLOCK_IN",  "occurredAt": "2026-04-07T09:00:00",
      "source": "WEB", "revoked": false },
    { "id": "...", "type": "CLOCK_OUT", "occurredAt": "2026-04-07T18:00:00",
      "source": "WEB", "revoked": true,
      "revocation": { "reason": "退勤打刻の時刻誤り", "recordedBy": "佐藤 花子",
                      "recordedAt": "2026-04-08T10:00:00" } },
    { "id": "...", "type": "CLOCK_OUT", "occurredAt": "2026-04-07T19:00:00",
      "source": "ADMIN", "revoked": false }
  ]
}
```

---

## 3. 日次勤怠

### 3.1 `GET /api/employees/{id}/attendances`

| クエリパラメータ | 型 | 必須 | 説明 |
| --- | --- | --- | --- |
| `month` | `YYYY-MM` | ○ | 対象月 |

```json
{
  "month": "2026-04",
  "days": [ /* DailyAttendance の配列。3.2 と同じ形 */ ],
  "totals": {
    "attendedDays": 20,
    "workingMinutes": 10380,
    "overtimeWithinStatutoryMinutes": 0,
    "overtimeBeyondStatutoryMinutes": 1500,
    "nightMinutes": 300,
    "legalHolidayMinutes": 0
  },
  "warnings": [
    { "workDate": "2026-04-07", "type": "BREAK_TIME_SHORTAGE",
      "message": "実労働 9 時間に対し休憩が 45 分です。60 分以上必要です" }
  ]
}
```

> **この応答に「時間外労働の合計」や「36 協定の消化率」は含めない。**
> フレックスタイム制では日次の残業が確定せず、月次の清算で初めて時間外労働が決まる
> （BR-05）。ここで返す `overtimeBeyondStatutoryMinutes` は
> **固定時間制の日次判定を単純に合計したもの**であり、フレックスでは意味を持たない。
> 制度を問わない月次の確定値は
> [月次清算 API](../04_勤怠_月次清算/API設計書.md) が返す。

**日次と月次で責務を分けることが、フレックスを正しく扱うための前提である。**

### 3.2 `GET /api/employees/{id}/attendances/{date}`

2.1 の応答に含まれる `attendance` と同じ形を返す。
内訳（`slices`）を必ず含める。

### 3.3 `POST /api/employees/{id}/attendances/{date}/recalculation`

打刻の訂正や就業規則の改定を反映するために、明示的に再計算する。

| 応答 | 条件 |
| --- | --- |
| `200 OK` | 再計算した結果を返す |
| `409 month-already-closed` | 締め済みの月 |
| `422 work-rule-not-found` | その日に適用される就業規則が無い |

**再計算は自動では走らない。**
就業規則を改定した瞬間に過去の全社員の勤怠が変わると、
確定済みの値が予告なく動く。人事が対象を選んで明示的に実行する。

---

## 4. 実装上の注意

| # | 内容 |
| --- | --- |
| 1 | 打刻の登録前に「既存の打刻 + 今回の打刻」で遷移を検証する。不正なら **DB に書かずに** 弾く |
| 2 | 勤務日の解決（BR-03）は `application` 層で行う。`CLOCK_IN` 以外は未退勤の勤務日を探す |
| 3 | 締め状態の確認は `approval` コンテキストへ問い合わせる。`attendance` が締めの状態を持たない |
| 4 | 内訳を別クエリで取ると N+1 になる。`GET /attendances` は 1 クエリで日次と内訳をまとめて取得する |
| 5 | `occurredAt` の既定値は `application` 層が `Clock` から解決する（AR-09） |

---

## 5. 未決事項

| # | 内容 | 判断の時期 |
| --- | --- | --- |
| 1 | 打刻の重複防止（同じ操作を連打したときの扱い）。冪等キーを導入するか | M1-a の実装時 |
| 2 | 未来日の打刻を許すか | M1-a の実装時 |
