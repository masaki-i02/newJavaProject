# 勤怠（打刻・日次集計） API設計書

| 項目 | 内容 |
| --- | --- |
| 文書番号 | KNT-DES-303 |
| 版 | 0.2 |
| 対象パッケージ | `jp.co.sample.kintai.attendance.presentation` |
| 関連文書 | [ドメインモデル設計書](ドメインモデル設計書.md) / [DB設計書](DB設計書.md) / [設計規約チェックリスト](../00_共通/設計規約チェックリスト.md) |
| 改訂 | 0.2（2026-09-01）設計レビュー第 2 回の指摘を反映 |

共通仕様（形式・日時・`date` パラメータ・エラー・楽観ロック）は
[社員・組織 API設計書 1 章](../01_社員・組織/API設計書.md#1-共通仕様) に従う。

---

## 1. エンドポイント一覧

| メソッド | パス | 概要 | 必要ロール |
| --- | --- | --- | --- |
| `POST` | `/api/employees/{id}/time-clocks` | 打刻する | 本人 |
| `GET` | `/api/employees/{id}/time-clocks` | 指定日の打刻（取消済みを含む履歴） | 本人 / 上長 / `HR` / `ADMIN` |
| `GET` | `/api/employees/{id}/attendances` | 月次の日次勤怠一覧 | 本人 / 上長 / `HR` / `ADMIN` |
| `GET` | `/api/employees/{id}/attendances/{date}` | 指定日の日次勤怠（内訳つき） | 本人 / 上長 / `HR` / `ADMIN` |
| `POST` | `/api/employees/{id}/attendances/{date}/recalculation` | 日次勤怠の再計算 | `HR` |
| `GET` | `/api/employees/{id}/attendances/current` | 現在の勤務状態（打刻画面用） | 本人 |

**打刻の訂正はこのコンテキストの API に含まれない。**
訂正は申請と承認を伴うため、`approval` コンテキストが受け付ける（BR-09）。
承認された結果として、取消行と新しい打刻行がここへ追記される。

---

## 2. 打刻

### 2.1 `POST /api/employees/{id}/time-clocks`

```json
{ "type": "CLOCK_IN", "occurredAt": "2026-04-07T09:00:30" }
```

| 項目 | 必須 | 説明 |
| --- | --- | --- |
| `type` | ○ | `CLOCK_IN` / `CLOCK_OUT` / `BREAK_START` / `BREAK_END` |
| `occurredAt` | — | 省略時は `application` 層が `Clock` から解決した現在時刻。**秒まで受け付ける** |

`source` はリクエストで指定できない。画面からの打刻は必ず `WEB` になる。
訂正申請の承認による追記（`CORRECTION`）は `approval` 経由でしか起きない。

応答（勤務が完了していない場合）:

```json
{ "workDate": "2026-04-07", "status": "WORKING", "closed": false,
  "calculationStatus": "NOT_CLOSED", "unclosedWorkDates": [] }
```

応答（退勤まで完了した場合）:

```json
{
  "workDate": "2026-04-07",
  "status": "FINISHED",
  "closed": true,
  "calculationStatus": "CALCULATED",
  "unclosedWorkDates": [],
  "attendance": {
    "workDate": "2026-04-07",
    "dayType": "WORKDAY",
    "workingTimeSystem": "FIXED",
    "version": 1,
    "workingMinutes": 780,
    "breakMinutes": 60,
    "baseMinutes": 480,
    "overtimeWithinStatutoryMinutes": 0,
    "overtimeBeyondStatutoryMinutes": 300,
    "nightMinutes": 300,
    "legalHolidayMinutes": 0,
    "breakRequirementSatisfied": true,
    "slices": [
      { "calendarDate": "2026-04-07", "startedAt": "2026-04-07T13:00:00",
        "endedAt": "2026-04-07T18:00:00", "minutes": 300, "premiums": [] },
      { "calendarDate": "2026-04-07", "startedAt": "2026-04-07T19:00:00",
        "endedAt": "2026-04-07T22:00:00", "minutes": 180, "premiums": [] },
      { "calendarDate": "2026-04-07", "startedAt": "2026-04-07T22:00:00",
        "endedAt": "2026-04-08T00:00:00", "minutes": 120,
        "premiums": ["NIGHT", "OVERTIME_BEYOND_STATUTORY"] },
      { "calendarDate": "2026-04-08", "startedAt": "2026-04-08T00:00:00",
        "endedAt": "2026-04-08T03:00:00", "minutes": 180,
        "premiums": ["NIGHT", "OVERTIME_BEYOND_STATUTORY"] }
    ]
  }
}
```

`calculationStatus` は日次計算の結果を表す。

| 値 | 意味 | `attendance` |
| --- | --- | --- |
| `CALCULATED` | 計算できた | 含む |
| `NOT_CLOSED` | まだ退勤していない | 含まない |
| `WORK_RULE_NOT_FOUND` | その日に適用される就業規則が無い | 含まない |

**打刻の記録は成功し、計算だけが行われない状態を表現する。**
就業規則の未設定を理由に打刻を拒否すると、働いた証拠が残らない
（[ドメインモデル設計書 4.2](ドメインモデル設計書.md)）。

| 決定 | 理由 |
| --- | --- |
| 時間を分単位の整数で返す | ISO-8601 の `PT8H30M` は加工が面倒。小数の「時間」は丸め誤差を生む |
| 勤務日（`workDate`）を必ず返す | 日をまたぐ勤務では、打刻した日と勤務日が一致しない（BR-03）。利用者に「これは前日の勤務です」と示す必要がある |
| 内訳（`slices`）を返す | 「なぜこの残業時間か」を画面で説明できるようにする |
| `slices` に `calendarDate` を持たせる | 法定休日労働は暦日で判断する。どの暦日の分かをデータで説明できるようにする（[ドメインモデル設計書 2.4](ドメインモデル設計書.md)） |
| 退勤前は `attendance` を返さない | 勤務が完了していない時点の集計値は意味を持たない |
| 秒を含む打刻を受け付ける | 端末が秒まで送っても拒否しない。分へそろえるのはサーバの仕事（BR-01） |

#### エラー

| `type` | HTTP | 発生条件 |
| --- | --- | --- |
| `urn:kintai:error:invalid-time-clock-sequence` | 422 | 打刻の順序が状態機械に反する（二重の出勤など）。**打刻を足しても直らないので訂正申請へ案内する** |
| `urn:kintai:error:month-already-closed` | 409 | 締め済みの月への打刻 |

**拒否するのはこの 2 つだけである。**

> **`incomplete-time-clock-sequence` はここには現れない。**
> 「まだ退勤していない」は打刻を受け付ける時点では正常な状態であり、
> 拒否する理由が無い。この `type` が返るのは
> **労働時間の確定を要求したとき**（日次勤怠の取得・月次の提出）である。

| `type` | HTTP | 発生条件 | 利用者への案内 |
| --- | --- | --- | --- |
| `urn:kintai:error:incomplete-time-clock-sequence` | 409 | 退勤打刻が無いまま労働時間を求めた | **退勤を打てば解消する** |

2 つを分けるのは、**画面が案内を出し分けられるようにするため**である。
1 つにまとめると、退勤し忘れた社員に「訂正申請をしてください」と案内することになる。

| 状況 | 第 1 版 | 版 0.2 |
| --- | --- | --- |
| 前の勤務日が未退勤 | `409` で拒否 | **打刻は成功。`unclosedWorkDates` を警告として返す** |
| 就業規則が未設定 | `422` で拒否 | **打刻は成功。`calculationStatus = WORK_RULE_NOT_FOUND`** |

第 1 版は、別の日の不整合や計算側の都合で **打刻そのものを拒否していた。**
訂正申請の承認が下りるまで当日の打刻が一切できず、
労働の証拠が残らない運用のデッドロックになる（BR-09 は承認を要求する）。

打刻は労働時間の一次証拠である。**記録を止めてよいのは、
記録そのものが不正なとき（順序違反）と、書き換えてはいけないとき（締め済み）だけ。**

締め済みの判定は **`workDate` の属する月**で行う。
3/31 22:00 出勤 → 4/1 06:00 退勤の退勤打刻は 3 月として判定する。
打刻時刻の月で判定すると、締め済みの 3 月分を 4 月扱いで書き込めてしまう。

未退勤の勤務日は応答に含める。

```json
{
  "workDate": "2026-04-08",
  "status": "WORKING",
  "closed": false,
  "calculationStatus": "NOT_CLOSED",
  "unclosedWorkDates": ["2026-04-03", "2026-04-06"]
}
```

**配列で返す。** 前日 1 日だけを見ると、金曜に退勤を打ち忘れて
月曜に出勤したケースを取りこぼす。締めていない全期間から探す
（[DB設計書 4.2](DB設計書.md)）。

画面は「4/3・4/6 の退勤打刻がありません」と示し、訂正申請へ誘導する。

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
  "unclosedWorkDates": []
}
```

**`availableActions` をサーバが返す。**
状態機械（BR-02）はドメインの知識であり、フロントエンドに複製すると
仕様変更のたびに 2 か所を直すことになる。ボタンの活性制御はこの配列に従う。

`unclosedWorkDates` が空でなければ、画面は打刻ボタンと並べて
「◯◯ の退勤打刻がありません」を表示し、訂正申請へ誘導する。
**打刻ボタンは消さない。** その日の労働の記録を止めてはいけない。

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
      "source": "CORRECTION", "reason": "退勤打刻の時刻誤り", "revoked": false }
  ]
}
```

---

## 3. 日次勤怠

### 3.1 `GET /api/employees/{id}/attendances`

| クエリパラメータ | 型 | 必須 | 説明 |
| --- | --- | --- | --- |
| `from` | `date` | ○ | 開始日（含む） |
| `toExclusive` | `date` | ○ | 終了日（含まない） |

**期間は半開区間で受ける。** 月中入社の初月は「入社日から翌月 1 日まで」になり、
暦月に固定できないため（[就業規則 3.2](../02_就業規則・カレンダー/ドメインモデル設計書.md)）。

```json
{
  "from": "2026-04-01",
  "toExclusive": "2026-05-01",
  "days": [ /* DailyAttendance の配列。3.2 と同じ形 */ ],
  "totals": {
    "attendedDays": 20,
    "workingMinutes": 10380,
    "breakMinutes": 1200,
    "baseMinutes": 8880,
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

`totals` に排他区分をすべて含めるのは、
**`baseMinutes + overtimeWithin + overtimeBeyond + legalHoliday = workingMinutes`
をクライアント側でも検算できるようにする**ためである。
これが本設計の看板であり、応答から検算できないと意味がない。

> **この応答に「時間外労働の合計」や「36 協定の消化率」は含めない。**
> 日次の合計は、**制度を問わず**月次の確定値ではない。
>
> | 制度 | 日次で確定しないもの |
> | --- | --- |
> | 固定時間制 | **週 40 時間超（BR-04）。** 週は日次では閉じない |
> | フレックス | **時間外労働そのもの（BR-05）。** 清算期間の総労働時間で決まる |
>
> 本コンテキストが保証するのは、`workingMinutes − overtimeBeyondStatutoryMinutes
> − legalHolidayMinutes` が **週次判定の材料になる法定内労働時間**であること。
> 制度を問わない月次の確定値は
> [月次清算 API](../04_勤怠_月次清算/API設計書.md) が返す。

**日次と月次で責務を分けることが、フレックスを正しく扱うための前提である。**

### 3.2 `GET /api/employees/{id}/attendances/{date}`

2.1 の応答に含まれる `attendance` と同じ形を返す。
内訳（`slices`）を必ず含める。

### 3.3 `POST /api/employees/{id}/attendances/{date}/recalculation`

打刻の訂正や就業規則の改定を反映するために、明示的に再計算する。

```json
{ "version": 3 }
```

`daily_attendances` は UPDATE される表なので、
[共通仕様 1.4](../01_社員・組織/API設計書.md) に従って `version` を必須とする。
2 人が同時に再計算を指示したときの上書きを検出する。

| 応答 | 条件 |
| --- | --- |
| `200 OK` | 再計算した結果を返す。`version` と `calculatedAt` が更新される |
| `403 forbidden` | `HR` ロールが無い |
| `404 resource-not-found` | 対象の日次勤怠が存在しない |
| `409 month-already-closed` | 締め済みの月 |
| `409 optimistic-lock-failure` | `version` が一致しない |
| `422 work-rule-not-found` | その日に適用される就業規則が無い |

**就業規則の改定を契機とする再計算は自動では走らない。**
改定した瞬間に過去の全社員の勤怠が変わると、確定済みの値が予告なく動く。
人事が対象を選んで明示的に実行する。

一方 **打刻の訂正が承認されたときは自動で再計算する。**
訂正の目的が計算結果の是正であり、反映されなければ訂正した意味がないため
（[05_申請承認と締め 4.3](../05_申請承認と締め/ドメインモデル設計書.md)）。

---

## 4. 実装上の注意

| # | 内容 |
| --- | --- |
| 1 | 打刻の登録前に「既存の打刻 + 今回の打刻」で遷移を検証する。不正なら **DB に書かずに** 弾く |
| 2 | 勤務日の解決（BR-03）は `application` 層で行う。`CLOCK_IN` 以外は未退勤の勤務日を探す |
| 3 | 締め状態の確認は **`shared.domain.MonthClosureQuery`**（実装は `approval/infrastructure`）で行う。`approval` の型は参照しない。判定に使う月は `workDate` の月 |
| 4 | 内訳を別クエリで取ると N+1 になる。`GET /attendances` は 1 クエリで日次と内訳をまとめて取得する |
| 5 | `occurredAt` の既定値は `application` 層が `Clock` から解決する（AR-09） |
| 6 | 秒を分へそろえるのは `toWorkedRanges()` の中で 1 回だけ。区間ごとに丸めると内訳の合計が実労働と合わなくなる（BR-01） |
| 7 | 打刻の追記と日次計算は別のトランザクションにしない。同一トランザクション内で、計算の失敗は打刻をロールバックさせずに握って `calculationStatus` へ落とす |

---

## 5. 未決事項

| # | 内容 | 判断の時期 |
| --- | --- | --- |
| 1 | 打刻の重複防止（同じ操作を連打したときの扱い）。冪等キーを導入するか | M1-a の実装時 |
| 2 | 未来日の打刻を許すか | M1-a の実装時 |
| 3 | `POST /time-clocks` の成功時ステータスを `200` にするか `201 + Location` にするか | M1-a の実装時 |
| 4 | 未退勤の勤務日が一定日数を超えたら通知するか | 運用設計時 |
