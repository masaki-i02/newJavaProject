# 勤怠（月次清算） API設計書

| 項目 | 内容 |
| --- | --- |
| 文書番号 | KNT-DES-403 |
| 版 | 0.2 |
| 対象パッケージ | `jp.co.sample.kintai.attendance.presentation` |
| 関連文書 | [ドメインモデル設計書](ドメインモデル設計書.md) / [DB設計書](DB設計書.md) / [設計規約チェックリスト](../00_共通/設計規約チェックリスト.md) |
| 改訂 | 0.2（2026-09-01）設計レビュー第 2 回の指摘を反映 |

共通仕様は [社員・組織 API設計書 1 章](../01_社員・組織/API設計書.md#1-共通仕様) に従う。

---

## 1. エンドポイント一覧

| メソッド | パス | 概要 | 必要ロール |
| --- | --- | --- | --- |
| `GET` | `/api/employees/{id}/settlements/{month}` | 月次清算の結果 | 本人 / 上長 / `HR` / `ADMIN` |
| `POST` | `/api/employees/{id}/settlements/{month}/recalculation` | 月次清算の再計算 | `HR` |
| `GET` | `/api/settlements/agreement-alerts` | 36 協定の超過者一覧 | `HR` |

**再計算を本人に開放しない。** 要件定義書 4 章の `EMPLOYEE` の権限は
「自分の打刻、自分の勤怠閲覧、打刻の訂正申請、月次勤怠の提出」であり、再計算は含まれない。
提出を契機とする再計算は提出処理の内部で行う（3.1）。

---

## 2. `GET /api/employees/{id}/settlements/{month}`

フレックスタイム制の場合:

```json
{
  "month": "2026-05",
  "period": { "from": "2026-05-01", "toExclusive": "2026-06-01" },
  "version": 2,
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
    "annualUsedBeforeMinutes": 1830,
    "annualLimitMinutes": 21600,
    "exceedsAnnual": false
  },
  "calculatedAt": "2026-06-03T18:22:11"
}
```

| 決定 | 理由 |
| --- | --- |
| `period` を返す | 清算期間は暦月とは限らない。月中入社・月中退職の月は在籍期間との交差になる。画面が「5/1 〜 5/31」と出せない月がある |
| `annualUsedBeforeMinutes` という名前にする | 中身は **当月より前**の年度累計であり、当月を含まない。`annualUsedMinutes` では当月を含むように読める |
| `version` を返す | 再計算リクエストで必須になるため、取得する経路が要る（[共通仕様 1.4](../01_社員・組織/API設計書.md)） |

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

```json
{ "version": 2 }
```

| 応答 | 条件 |
| --- | --- |
| `200 OK` | 再計算した結果を返す |
| `403 forbidden` | `HR` ロールが無い |
| `409 month-already-closed` | 締め済みの月 |
| `409 optimistic-lock-failure` | `version` が一致しない |
| `409 daily-attendance-incomplete` | **未計算の勤務日が残っている** |
| `422 work-rule-not-found` | 対象月に適用される就業規則が無い |
| `422 working-time-system-changed-mid-month` | 月の途中で**労働時間制度**が変わっている |

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

未計算の日を探す範囲は **清算期間そのものではなく、週次判定に必要な範囲**である
（[ドメインモデル設計書 3.2](ドメインモデル設計書.md)）。
月初の週は前月の日を含むので、清算期間の中だけを見ると
**前月の日が欠けたまま週 40 時間超を判定してしまう。**

#### 「制度の変更」と「規則の改定」を区別する

| 何が変わったか | 月の途中で起きうるか | 扱い |
| --- | --- | --- |
| 労働時間制度（固定 ⇄ フレックス） | **起きない**（月初日か入社日に限る） | `422` で拒否 |
| 就業規則の版（改定） | **起きる**（正常な運用） | 日ごとに版を引いて計算する |

第 1 版はエラー型を `work-rule-changed-mid-month` としており、
名前が「版の変更」を指していた。
[就業規則 3](../02_就業規則・カレンダー/ドメインモデル設計書.md) は
月中の改定を正常な運用としているので、**適法な改定月を 422 で弾いてしまう。**
`working-time-system-changed-mid-month` に改める。

なお適用開始日は「月初日、**または当該社員の入社日**」である
（[就業規則 API設計書 2.3](../02_就業規則・カレンダー/API設計書.md)）。
入社日から始まる月は清算期間が在籍期間との交差になるので、制度が割れる問題は起きない。

### 3.1 再計算のタイミング

| 契機 | 実行者 |
| --- | --- |
| 社員が月次勤怠を提出するとき | システム（提出処理の一部） |
| 打刻の訂正が承認されたとき | システム（承認処理の一部） |
| **過去月を再計算したとき、同一年度の後続月** | システム（連鎖して実行） |
| 人事が明示的に指示したとき | `HR` |

**定期バッチで全社員を再計算しない。**
確定済みの値が予告なく動くと、給与計算との突合ができなくなるため。

> **過去月の再計算は、同一年度の後続月へ連鎖させる。**
> `annual_agreement_subject_before_minutes` は保存値なので、
> 過去月の時間外が変わっても後続月の年次上限の判定は自動では変わらない。
> 訂正の承認による過去月の再計算は正規の契機として認めているので、必ず起きる
> （[DB設計書 4.2](DB設計書.md)）。
> 締め済みの月は再計算しないので、連鎖は未締めの月までで止まる。

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
      "employeeId": "0195c000-0000-7000-8000-000000000001",
      "subjectMinutes": 2820,
      "monthlyLimitMinutes": 2700,
      "exceedsMonthly": true,
      "annualUsedBeforeMinutes": 20400,
      "annualLimitMinutes": 21600,
      "exceedsAnnual": false
    }
  ],
  "summary": { "monthlyExceeded": 3, "annualExceeded": 1 }
}
```

**社員番号・氏名・部署を返さない。** それらは `employee` コンテキストが所有する概念であり、
`attendance` の応答に混ぜると、こちらが持っていない情報の提供者になってしまう
（[設計規約チェックリスト 3](../00_共通/設計規約チェックリスト.md)）。

画面は `GET /api/employees?ids=...&date=2026-05-01` で氏名と所属を引く。
所属の基準日を対象月初日にそろえるのは、BR-11 の基準日と一致させるためである。

---

## 5. 実装上の注意

| # | 内容 |
| --- | --- |
| 1 | 月次清算は日次勤怠の合計で成り立つ。**日次を 1 クエリでまとめて取得する。** 日ごとに問い合わせると N+1 になる |
| 2 | 取得する範囲は清算期間ではなく **週次判定に必要な範囲**（前月の日を含む） |
| 3 | 会社カレンダーもまとめて取得する（所定労働日数の算出に必要）。範囲は清算期間 |
| 4 | 年度累計は `annual_agreement_subject_before_minutes` に保存済みの値を使う。毎回全月を再集計しない。ただし過去月を再計算したら後続月へ連鎖させる（3.1） |
| 5 | 制度ごとの計算の分岐は `workrule` の `sealed interface` に対する `switch` で行う。判別用の enum を新設しない |
| 6 | 締め状態の確認は **`shared.domain.MonthClosureQuery`**（実装は `approval/infrastructure`）で行う。`approval` の型は参照しない |
| 7 | 総枠と所定総労働時間は `workrule` の実装を呼ぶ。本コンテキストで計算式を持たない |

---

## 6. 未決事項

| # | 内容 | 判断の時期 |
| --- | --- | --- |
| 1 | 36 協定の超過を検知したときに通知（メール等）を送るか | M2 |
| 2 | 月次清算の結果を CSV で出力する形式 | M3（給与連携） |
