# 就業規則・カレンダー API設計書

| 項目 | 内容 |
| --- | --- |
| 文書番号 | KNT-DES-203 |
| 版 | 0.1 |
| 対象パッケージ | `jp.co.sample.kintai.workrule.presentation` |
| 関連文書 | [ドメインモデル設計書](ドメインモデル設計書.md) / [DB設計書](DB設計書.md) |

共通仕様（形式・エラー・日時の扱い）は
[社員・組織 API設計書 1章](../01_社員・組織/API設計書.md#1-共通仕様) に従う。

---

## 1. エンドポイント一覧

| メソッド | パス | 概要 | 必要ロール |
| --- | --- | --- | --- |
| `GET` | `/api/work-rules` | 就業規則の一覧 | `HR` |
| `GET` | `/api/work-rules/{id}` | 就業規則の詳細 | `HR` |
| `POST` | `/api/work-rules` | 就業規則の新規登録 | `HR` |
| `POST` | `/api/work-rules/{id}/revisions` | 就業規則の改定 | `HR` |
| `POST` | `/api/employees/{employeeId}/work-rule-assignments` | 社員への適用・変更 | `HR` |
| `GET` | `/api/employees/{employeeId}/work-rule-assignments` | 適用履歴 | `HR` または本人 |
| `GET` | `/api/calendars` | 会社カレンダーの取得 | `EMPLOYEE` |
| `PUT` | `/api/calendars/{date}` | 暦日区分の設定 | `HR` |
| `POST` | `/api/calendars/bulk` | 期間を指定した一括設定 | `HR` |

---

## 2. 就業規則

### 2.1 `GET /api/work-rules/{id}`

労働時間制度によって返す項目が変わる。**`workingTimeSystem` で判別する。**

固定時間制の場合:

```json
{
  "id": "0195a000-0000-7000-8000-000000000001",
  "name": "標準勤務",
  "workingTimeSystem": "FIXED",
  "validFrom": "2024-04-01",
  "validTo": null,
  "fixedTime": {
    "scheduledStart": "09:00",
    "scheduledEnd": "18:00",
    "scheduledBreakMinutes": 60,
    "scheduledWorkingMinutes": 480
  },
  "statutoryDailyMinutes": 480,
  "statutoryWeeklyMinutes": 2400,
  "nightWindow": { "start": "22:00", "end": "05:00" },
  "premiumRates": { "overtimeBeyondStatutory": "0.250", "night": "0.250", "legalHoliday": "0.350" }
}
```

フレックスタイム制の場合:

```json
{
  "id": "0195a000-0000-7000-8000-000000000002",
  "name": "フレックス勤務",
  "workingTimeSystem": "FLEX",
  "validFrom": "2024-04-01",
  "validTo": null,
  "flextime": {
    "flexibleStart": "07:00",
    "flexibleEnd": "22:00",
    "coreStart": "11:00",
    "coreEnd": "15:00",
    "standardDailyMinutes": 480
  },
  "statutoryDailyMinutes": 480,
  "statutoryWeeklyMinutes": 2400,
  "nightWindow": { "start": "22:00", "end": "05:00" },
  "premiumRates": { "overtimeBeyondStatutory": "0.250", "night": "0.250", "legalHoliday": "0.350" }
}
```

| 決定 | 理由 |
| --- | --- |
| 制度ごとの項目を `fixedTime` / `flextime` の入れ子に分ける | 平坦に並べると「FLEX なのに scheduledStart がある」形になり、DB の CHECK 制約が禁じた状態を API が再現してしまう |
| 使わないほうのキーは **出力しない**（`null` も出さない） | TypeScript 側で判別可能なユニオン型として扱えるようにするため |
| 割増率を文字列で返す | `0.250` を JSON の数値にすると、受け手の言語によっては浮動小数点になり丸め誤差が入る |

TypeScript 側では次のように受ける。

```typescript
type WorkRule =
  | { workingTimeSystem: 'FIXED'; fixedTime: FixedTime; /* 共通項目 */ }
  | { workingTimeSystem: 'FLEX';  flextime: Flextime;   /* 共通項目 */ }
```

**ドメインの `sealed interface` が、DB の CHECK 制約と TypeScript の判別可能ユニオンに対応する。**
3 つの層で同じ制約が表現されている状態を保つ。

### 2.2 `POST /api/work-rules/{id}/revisions`（改定）

```json
{
  "validFrom": "2026-10-01",
  "fixedTime": { "scheduledStart": "09:00", "scheduledEnd": "17:30", "scheduledBreakMinutes": 60 }
}
```

**改定は既存レコードの更新ではなく、期間を区切って新しい規則を作る操作である。**
現行規則の `validTo` を `validFrom` で閉じ、新しい行を作る。
これにより、改定前の月を再計算しても当時の規則が適用される。

| 応答 | 条件 |
| --- | --- |
| `201 Created` | 改定成功 |
| `409` | 指定日以降に既に別の版がある |
| `422` | 割増率が法定下限を下回る、コアタイムがフレキシブルタイムの外にある等 |

> 法定下限を下回る指定は「入力形式は正しいが業務上受け付けられない」ため、
> `400` ではなく `422 Unprocessable Content` を返す。

### 2.3 `POST /api/employees/{employeeId}/work-rule-assignments`

```json
{ "workRuleId": "0195a000-0000-7000-8000-000000000002", "validFrom": "2026-10-01" }
```

社員の勤務形態を変更する（固定 → フレックスなど）。
現在の適用を閉じて新しい適用を開く。期間の重複は DB の排他制約が拒否する。

**変更は月初日にのみ許可する。** フレックスの清算期間は月単位であり、
月の途中で制度が変わると、その月の総労働時間をどちらの制度で判定するか決められないため。

| 応答 | 条件 |
| --- | --- |
| `201 Created` | 適用成功 |
| `409` | 指定日以降に既に別の適用がある |
| `422` | `validFrom` が月初日でない |

---

## 3. 会社カレンダー

### 3.1 `GET /api/calendars`

| クエリパラメータ | 型 | 必須 | 説明 |
| --- | --- | --- | --- |
| `from` | `date` | ○ | 開始日 |
| `to` | `date` | ○ | 終了日（含む） |

```json
{
  "from": "2026-05-01",
  "to": "2026-05-31",
  "days": [
    { "date": "2026-05-01", "dayType": "WORKDAY", "name": null },
    { "date": "2026-05-02", "dayType": "NON_LEGAL_HOLIDAY", "name": "所定休日" },
    { "date": "2026-05-03", "dayType": "LEGAL_HOLIDAY", "name": "憲法記念日" }
  ],
  "workdayCount": 21
}
```

**未登録の日も `WORKDAY` として配列に含めて返す。**
「配列に無い日は所定労働日」という暗黙のルールをクライアントに持たせない。

`workdayCount` を含めるのは、フレックスの所定総労働時間を画面に表示するためである。

### 3.2 `POST /api/calendars/bulk`

年度初めにまとめて登録するための一括設定。

```json
{
  "from": "2027-01-01",
  "to": "2027-12-31",
  "rules": [
    { "dayOfWeek": "SUNDAY",   "dayType": "LEGAL_HOLIDAY",     "name": "法定休日" },
    { "dayOfWeek": "SATURDAY", "dayType": "NON_LEGAL_HOLIDAY", "name": "所定休日" }
  ],
  "overrides": [
    { "date": "2027-01-01", "dayType": "NON_LEGAL_HOLIDAY", "name": "元日" }
  ]
}
```

曜日の規則を先に適用し、そのあと `overrides` で個別の日を上書きする。
祝日は曜日で決まらないため、この 2 段構えが必要になる。

| 応答 | 条件 |
| --- | --- |
| `200 OK` | 登録件数と内訳を返す |
| `409` | 既に締め済みの月を含む（`approval` へ問い合わせて判定する） |

> **締め済みの月のカレンダーは変更できない。** 暦日区分が変わると休日割増の計算が変わり、
> 確定済みの勤怠と矛盾するため。この判定は `approval` コンテキストへの問い合わせになる。

---

## 4. 実装上の注意

| # | 内容 |
| --- | --- |
| 1 | 労働時間制度ごとのレスポンス組み立ては、ドメインの `sealed interface` に対する `switch` で行う。制度を追加したときにコンパイルエラーで気づけるようにする |
| 2 | 割増率の JSON 出力は `BigDecimal` を文字列としてシリアライズする（`@JsonFormat(shape = STRING)`） |
| 3 | カレンダーの一括設定は件数が多いため、1 件ずつ INSERT せず `INSERT ... ON CONFLICT DO UPDATE` でまとめる |

---

## 5. 未決事項

| # | 内容 | 判断の時期 |
| --- | --- | --- |
| 1 | 祝日の自動取込 API を設けるか（内閣府 CSV の URL を指定して取り込む） | M1-a の実装時 |
| 2 | 就業規則の改定時に、影響を受ける未締めの月を自動で再計算するか | 05_申請承認と締め の設計時 |
