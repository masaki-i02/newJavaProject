# 勤怠（月次清算） API設計書

| 項目 | 内容 |
| --- | --- |
| 文書番号 | KNT-DES-403 |
| 版 | 0.1 |
| 対象パッケージ | `jp.co.sample.kintai.attendance.presentation` |
| 関連文書 | [ドメインモデル設計書](ドメインモデル設計書.md) / [DB設計書](DB設計書.md) |

共通仕様は [社員・組織 API設計書 1 章](../01_社員・組織/API設計書.md#1-共通仕様) に従う。

---

## 1. エンドポイント一覧

| メソッド | パス | 概要 | 必要ロール |
| --- | --- | --- | --- |
| `GET` | `/api/employees/{id}/settlements/{month}` | 月次清算の結果 | 本人 / 上長 / `HR` |
| `POST` | `/api/employees/{id}/settlements/{month}/recalculation` | 月次清算の再計算 | 本人 / `HR` |
| `GET` | `/api/settlements/agreement-alerts` | 36 協定の超過者一覧 | `HR` |

---

## 2. `GET /api/employees/{id}/settlements/{month}`

フレックスタイム制の場合:

```json
{
  "month": "2026-05",
  "workingTimeSystem": "FLEX",
  "workingMinutes": 11100,
  "legalHolidayMinutes": 0,
  "targetWorkingMinutes": 11100,
  "scheduledTotalMinutes": 10560,
  "statutoryTotalLimitMinutes": 10628,
  "overtimeMinutes": 472,
  "shortageMinutes": 0,
  "nightMinutes": 0,
  "coreTimeAbsenceMinutes": 60,
  "agreement": {
    "subjectMinutes": 472,
    "monthlyLimitMinutes": 2700,
    "exceedsMonthly": false,
    "annualUsedMinutes": 1830,
    "annualLimitMinutes": 21600,
    "exceedsAnnual": false
  },
  "calculatedAt": "2026-06-01T02:00:00"
}
```

固定時間制の場合は、これに加えて週の内訳を返す。

```json
{
  "month": "2026-04",
  "workingTimeSystem": "FIXED",
  "dailyOvertimeMinutes": 900,
  "weeklyOvertimeMinutes": 120,
  "overtimeMinutes": 1020,
  "weeklyBreakdown": [
    { "weekStart": "2026-04-05", "weekEnd": "2026-04-11",
      "statutoryInsideMinutes": 2520, "overtimeMinutes": 120 }
  ]
}
```

| 決定 | 理由 |
| --- | --- |
| 制度によって返す項目を変える | フレックスに `weeklyBreakdown` は存在せず、固定時間制に `statutoryTotalLimitMinutes` は意味を持たない |
| **所定総労働時間と法定総枠の両方を返す** | 画面で「所定は超えたが時間外ではない」区間を説明するために必要 |
| 週の内訳を返す | どの週で超えたかを示せないと、労務の問い合わせに答えられない |
| 36 協定の情報を入れ子にする | 月次・年次の上限と消化状況をまとめて扱うため |

### 2.1 画面での見せ方（前提）

フレックスの場合、3 つの時間を並べて表示することを前提に設計している。

```
所定総労働時間  176h00m  ├──────────────────┤
法定総枠        177h08m  ├───────────────────┤
実労働          185h00m  ├────────────────────────┤
                                              └─ 時間外 7h52m
```

**「所定を超えたら残業」と誤解させない表示が必要である。**
フレックスでは所定と総枠の間に約 1 時間の区間があり、そこは時間外ではない。

---

## 3. `POST /api/employees/{id}/settlements/{month}/recalculation`

月次清算を計算し直す。

| 応答 | 条件 |
| --- | --- |
| `200 OK` | 再計算した結果を返す |
| `409 month-already-closed` | 締め済みの月 |
| `409 daily-attendance-incomplete` | **未計算の勤務日が残っている** |
| `422 work-rule-not-found` | 対象月に適用される就業規則が無い |
| `422 work-rule-changed-mid-month` | 月の途中で労働時間制度が変わっている |

```json
{
  "type": "urn:kintai:error:daily-attendance-incomplete",
  "title": "日次勤怠が未計算の日があります",
  "status": 409,
  "detail": "2026-05-12, 2026-05-20 の日次勤怠が確定していません",
  "incompleteDates": ["2026-05-12", "2026-05-20"]
}
```

**未計算の日を具体的に返す。** 月次清算は全日の合計で成り立つため、
1 日でも欠けると結果が過少になる。利用者はどの日を直せばよいかを知る必要がある。

> **月の途中で労働時間制度が変わることを許さない。**
> フレックスの清算期間は月単位であり、月の前半が固定・後半がフレックスだと
> その月の総労働時間をどちらの基準で判定するか決められない。
> [就業規則 API設計書 2.3](../02_就業規則・カレンダー/API設計書.md) で
> 適用開始日を月初日に限定しているのは、この制約のためである。

### 3.1 再計算のタイミング

| 契機 | 実行者 |
| --- | --- |
| 社員が月次勤怠を提出するとき | システム（提出処理の一部） |
| 打刻の訂正が承認されたとき | システム（承認処理の一部） |
| 人事が明示的に指示したとき | `HR` |

**定期バッチで全社員を再計算しない。**
確定済みの値が予告なく動くと、給与計算との突合ができなくなるため。

---

## 4. `GET /api/settlements/agreement-alerts`

人事が 36 協定の超過者を確認するための一覧。

| クエリパラメータ | 型 | 必須 | 説明 |
| --- | --- | --- | --- |
| `month` | `YYYY-MM` | ○ | 対象月 |
| `type` | `MONTHLY` / `ANNUAL` / `ALL` | — | 既定は `ALL` |

```json
{
  "month": "2026-05",
  "alerts": [
    {
      "employee": { "id": "...", "employeeNumber": "E0001", "name": "山田 太郎",
                    "department": { "code": "S1A", "name": "第一営業課" } },
      "subjectMinutes": 2820,
      "monthlyLimitMinutes": 2700,
      "exceedsMonthly": true,
      "annualUsedMinutes": 20400,
      "annualLimitMinutes": 21600,
      "exceedsAnnual": false
    }
  ],
  "summary": { "monthlyExceeded": 3, "annualExceeded": 1 }
}
```

**部署を含めて返す。** 人事は「どの部署で超過が起きているか」を見るため。
所属は対象月初日時点のものを使う（BR-11 の基準日と揃える）。

---

## 5. 実装上の注意

| # | 内容 |
| --- | --- |
| 1 | 月次清算は日次勤怠の合計で成り立つ。**日次を 1 クエリでまとめて取得する。** 日ごとに問い合わせると N+1 になる |
| 2 | 会社カレンダーも月ぶんまとめて取得する（所定労働日数の算出に必要） |
| 3 | 年度累計は `annual_overtime_before_minutes` に保存済みの値を使う。毎回全月を再集計しない |
| 4 | 制度ごとの計算の分岐は `sealed interface` に対する `switch` で行う |
| 5 | 締め状態の確認は `approval` コンテキストへ問い合わせる |

---

## 6. 未決事項

| # | 内容 | 判断の時期 |
| --- | --- | --- |
| 1 | 36 協定の超過を検知したときに通知（メール等）を送るか | M2 |
| 2 | 月次清算の結果を CSV で出力する形式 | M3（給与連携） |
