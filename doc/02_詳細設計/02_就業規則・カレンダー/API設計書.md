# 就業規則・カレンダー API設計書

| 項目 | 内容 |
| --- | --- |
| 文書番号 | KNT-DES-203 |
| 版 | 0.2 |
| 対象パッケージ | `jp.co.sample.kintai.workrule.presentation` |
| 関連文書 | [ドメインモデル設計書](ドメインモデル設計書.md) / [DB設計書](DB設計書.md) / [設計規約チェックリスト](../00_共通/設計規約チェックリスト.md) |
| 改訂 | 0.2（2026-09-01）設計レビュー第 2 回の指摘を反映 |

共通仕様（形式・エラー・日時の扱い・楽観ロック）は
[社員・組織 API設計書 1章](../01_社員・組織/API設計書.md#1-共通仕様) に従う。

---

## 1. エンドポイント一覧

| メソッド | パス | 概要 | 必要ロール |
| --- | --- | --- | --- |
| `GET` | `/api/work-rules` | 就業規則（系列）の一覧 | `HR` |
| `GET` | `/api/work-rules/{seriesId}` | 系列の詳細と版の履歴 | `HR` |
| `GET` | `/api/work-rules/{seriesId}/effective` | 指定日に有効な版 | `HR` |
| `POST` | `/api/work-rules` | 就業規則の新規登録（系列 + 初版） | `HR` |
| `POST` | `/api/work-rules/{seriesId}/revisions` | 就業規則の改定（版を 1 つ足す） | `HR` |
| `POST` | `/api/employees/{employeeId}/work-rule-assignments` | 社員への適用・変更 | `HR` |
| `GET` | `/api/employees/{employeeId}/work-rule-assignments` | 適用履歴 | `HR` または本人 |
| `GET` | `/api/work-rule-assignments/unassigned` | **規則が適用されていない在籍者の一覧** | `HR` |
| `GET` | `/api/calendars` | 会社カレンダーの取得 | `EMPLOYEE` |
| `PUT` | `/api/calendars/{date}` | 暦日区分の設定 | `HR` |
| `POST` | `/api/calendars/bulk` | 期間を指定した一括設定 | `HR` |

### 1.1 パスが指すのは系列であること

`/api/work-rules/{seriesId}` の `{seriesId}` は **系列**の識別子である。
版の識別子（`WorkRuleId`）は履歴の中にだけ現れる。

社員に適用するのも系列であり、版ではない。
版を適用してしまうと、改定した瞬間に全社員の規則が「未設定」になる
（[DB設計書 2.1](DB設計書.md)）。

### 1.2 このコンテキストのエラー型

[共通のエラー型](../01_社員・組織/API設計書.md#13-エラー応答) に加えて次を使う。

| `type` | HTTP | 発生条件 |
| --- | --- | --- |
| `urn:kintai:error:overlapping-period` | 409 | 版または適用の期間が重複 |
| `urn:kintai:error:month-already-closed` | 409 | 締め済みの月に影響する変更 |
| `urn:kintai:error:business-rule-violation` | 422 | 法定下限・上限違反、適用開始日が月初日でも入社日でもない 等 |

---

## 2. 就業規則

### 2.1 `GET /api/work-rules/{seriesId}`

系列の情報と、版の履歴を返す。

```json
{
  "seriesId": "0195a000-0000-7000-8000-000000000001",
  "name": "標準勤務",
  "abolishedOn": null,
  "version": 3,
  "revisions": [
    {
      "workRuleId": "0195b000-0000-7000-8000-000000000011",
      "validFrom": "2024-04-01",
      "validToExclusive": "2026-10-01",
      "workingTimeSystem": "FIXED",
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
    },
    {
      "workRuleId": "0195b000-0000-7000-8000-000000000012",
      "validFrom": "2026-10-01",
      "validToExclusive": null,
      "workingTimeSystem": "FIXED",
      "fixedTime": {
        "scheduledStart": "09:00",
        "scheduledEnd": "17:30",
        "scheduledBreakMinutes": 60,
        "scheduledWorkingMinutes": 450
      },
      "statutoryDailyMinutes": 480,
      "statutoryWeeklyMinutes": 2400,
      "nightWindow": { "start": "22:00", "end": "05:00" },
      "premiumRates": { "overtimeBeyondStatutory": "0.250", "night": "0.250", "legalHoliday": "0.350" }
    }
  ]
}
```

フレックスタイム制の版は `fixedTime` の代わりに `flextime` を持つ。

```json
{
  "workRuleId": "0195b000-0000-7000-8000-000000000021",
  "validFrom": "2024-04-01",
  "validToExclusive": null,
  "workingTimeSystem": "FLEX",
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
| 期間の上限を **`validToExclusive`** という名前で返す | 「その日を含むのか」を名前で示す。DB もドメインも半開区間で統一している |
| `version` は **系列**にだけ持たせる | 更新の対象は系列（改称・廃止）と適用であり、版は追記されるだけで書き換えない |

TypeScript 側では次のように受ける。

```typescript
type WorkRuleRevision =
  | { workingTimeSystem: 'FIXED'; fixedTime: FixedTime; /* 共通項目 */ }
  | { workingTimeSystem: 'FLEX';  flextime: Flextime;   /* 共通項目 */ }
```

**ドメインの `sealed interface` が、DB の CHECK 制約と TypeScript の判別可能ユニオンに対応する。**
3 つの層で同じ制約が表現されている状態を保つ。

### 2.2 `POST /api/work-rules/{seriesId}/revisions`（改定）

```json
{
  "version": 3,
  "validFrom": "2026-10-01",
  "fixedTime": { "scheduledStart": "09:00", "scheduledEnd": "17:30", "scheduledBreakMinutes": 60 }
}
```

**改定は既存レコードの更新ではなく、期間を区切って新しい版を作る操作である。**
現行版の `valid_to` を `validFrom` で閉じ、新しい行を作る。
**社員の適用行は一切書き換えない。** 適用は系列を指しているため、
新しい版は `validFrom` 以降の日付で自動的に選ばれる。

応答は 2.1 と同じ形に加えて `warnings` を含む。

```json
{
  "seriesId": "0195a000-0000-7000-8000-000000000001",
  "workRuleId": "0195b000-0000-7000-8000-000000000012",
  "version": 4,
  "warnings": [
    {
      "code": "schedule-exceeds-statutory-limit",
      "message": "2026年6月は所定総労働時間 10,560 分が法定労働時間の総枠 10,285 分を超えます",
      "period": { "from": "2026-06-01", "toExclusive": "2026-07-01" },
      "scheduledTotalMinutes": 10560,
      "statutoryTotalLimitMinutes": 10285
    }
  ]
}
```

| 応答 | 条件 |
| --- | --- |
| `201 Created` | 改定成功。`warnings` が空でないことがある |
| `409 overlapping-period` | 指定日以降に既に別の版がある |
| `409 optimistic-lock-failure` | `version` が一致しない |
| `409 month-already-closed` | `validFrom` が締め済みの月に入っている |
| `422 business-rule-violation` | 割増率が法定下限を下回る／法定労働時間が法定上限を超える／深夜帯が 22:00–05:00 でも 23:00–06:00 でもない／所定が法定を超える／休憩が労基法 34 条を下回る／コアタイムがフレキシブルの外 |

> 法定の範囲を外れる指定は「入力形式は正しいが業務上受け付けられない」ため、
> `400` ではなく `422 Unprocessable Content` を返す。

#### `warnings` を返す理由

フレックスでは、所定総労働時間が法定労働時間の総枠を超える月がある
（[ドメインモデル設計書 3.3](ドメインモデル設計書.md)）。
**違法ではないので登録は許すが、人事が気づかないまま運用されるのは避けたい。**

`422` にはしない。適法な状態を拒否すると、
実際にそういう規則を運用している会社では登録できなくなる。

改定の `validFrom` から 12 か月分を検査し、超過する月をすべて返す。

### 2.3 `POST /api/employees/{employeeId}/work-rule-assignments`

```json
{ "workRuleSeriesId": "0195a000-0000-7000-8000-000000000002", "validFrom": "2026-10-01" }
```

社員の勤務形態を変更する（固定 → フレックスなど）。
現在の適用を閉じて新しい適用を開く。期間の重複は DB の排他制約が拒否する。

**適用開始日は月初日、または当該社員の入社日に限る。**

| 開始日 | 可否 | 理由 |
| --- | --- | --- |
| 月初日 | ○ | 清算期間の境界と一致する |
| 入社日（月中） | ○ | **初月の清算期間は「入社日から翌月 1 日まで」になる**（[ドメインモデル設計書 3.2](ドメインモデル設計書.md)） |
| それ以外の月中の日 | × | 清算期間の途中で制度が変わると、その月の総労働時間をどちらの制度で判定するか決められない |

第 1 版は月初日だけを許していた。すると **月中に入社した社員に規則を適用できず、
初月の勤怠が計算できないまま締められない。**
入社は「変更」ではなく「開始」なので、清算期間が割れる問題が起きない。

| 応答 | 条件 |
| --- | --- |
| `201 Created` | 適用成功 |
| `409 overlapping-period` | 指定日以降に既に別の適用がある |
| `409 month-already-closed` | `validFrom` が締め済みの月に入っている |
| `422 business-rule-violation` | `validFrom` が月初日でも入社日でもない／入社日より前／退職済み／廃止済みの系列 |

### 2.4 `GET /api/work-rule-assignments/unassigned`

**在籍しているのに就業規則が適用されていない社員の一覧。**

```json
{
  "date": "2026-10-01",
  "employeeIds": [
    "0195c000-0000-7000-8000-000000000001",
    "0195c000-0000-7000-8000-000000000002"
  ]
}
```

| クエリパラメータ | 型 | 必須 | 説明 |
| --- | --- | --- | --- |
| `date` | `date` | – | 基準日。既定は `application` 層が `Clock` から解決した当日 |

**社員番号や氏名は返さない。** それらは `employee` コンテキストが所有する概念であり、
`workrule` の応答に混ぜると、こちらが持っていない情報の提供者になってしまう。
画面は `GET /api/employees?ids=...` で名前を引く
（[設計規約チェックリスト 3](../00_共通/設計規約チェックリスト.md)）。

この一覧が空でないと、その社員の勤怠は計算できない。
**DB では「在籍者全員に規則が適用されている」ことを守れない**ため、
画面で検知できるようにする（[DB設計書 5.1](DB設計書.md)）。

版に隙間がある場合（系列は適用されているが、その日に有効な版が無い）も
ここに現れる。呼び出し側から見れば、どちらも「規則が引けない」状態で区別する意味がない。

---

## 3. 会社カレンダー

### 3.1 `GET /api/calendars`

| クエリパラメータ | 型 | 必須 | 説明 |
| --- | --- | --- | --- |
| `from` | `date` | ○ | 開始日（含む） |
| `toExclusive` | `date` | ○ | **終了日（含まない）** |

```json
{
  "from": "2026-05-01",
  "toExclusive": "2026-06-01",
  "days": [
    { "date": "2026-05-01", "dayType": "WORKDAY", "name": null },
    { "date": "2026-05-02", "dayType": "NON_LEGAL_HOLIDAY", "name": "所定休日" },
    { "date": "2026-05-03", "dayType": "LEGAL_HOLIDAY", "name": "憲法記念日" }
  ],
  "workdayCount": 21
}
```

**期間は半開区間で受ける。** 内部の `DateRange` と同じ流儀にそろえ、
境界で 1 日ずれる不具合を作らない。
画面が「5 月」を表示するときは `from=2026-05-01&toExclusive=2026-06-01` を送る。

**未登録の日も `WORKDAY` として配列に含めて返す。**
「配列に無い日は所定労働日」という暗黙のルールをクライアントに持たせない。

`workdayCount` を含めるのは、フレックスの所定総労働時間を画面に表示するためである。

### 3.2 `POST /api/calendars/bulk`

年度初めにまとめて登録するための一括設定。

```json
{
  "from": "2027-01-01",
  "toExclusive": "2028-01-01",
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

応答は登録件数と警告を返す。

```json
{
  "registeredCount": 365,
  "byDayType": { "WORKDAY": 244, "LEGAL_HOLIDAY": 52, "NON_LEGAL_HOLIDAY": 69 },
  "warnings": [
    {
      "code": "no-legal-holiday-in-week",
      "message": "2027-08-09 から 2027-08-15 の 7 日間に法定休日がありません",
      "period": { "from": "2027-08-09", "toExclusive": "2027-08-16" }
    },
    {
      "code": "schedule-exceeds-statutory-limit",
      "message": "2027年6月は所定総労働時間 10,560 分が法定労働時間の総枠 10,285 分を超えます（フレックス勤務）",
      "period": { "from": "2027-06-01", "toExclusive": "2027-07-01" },
      "seriesId": "0195a000-0000-7000-8000-000000000002"
    }
  ]
}
```

| 応答 | 条件 |
| --- | --- |
| `200 OK` | 登録件数と内訳、警告を返す |
| `409 month-already-closed` | 期間に締め済みの月を含む |

#### 2 種類の警告

| コード | 内容 | 根拠 |
| --- | --- | --- |
| `no-legal-holiday-in-week` | 連続 7 日間に法定休日が 1 日も無い | 労基法 35 条。**DB では守れない**（[DB設計書 3.5](DB設計書.md)） |
| `schedule-exceeds-statutory-limit` | 所定総労働時間が法定労働時間の総枠を超える月がある | [ドメインモデル設計書 3.3](ドメインモデル設計書.md) |

カレンダーを変えると所定労働日数が変わるので、
**フレックスの系列すべてについて再検査する。**

#### 締め済みの月の判定について

**締め済みの月のカレンダーは変更できない。** 暦日区分が変わると休日割増の計算が変わり、
確定済みの勤怠と矛盾するため。

この判定は `shared.domain` の `MonthClosureQuery` ポート経由で行う。

```java
// shared/domain のインタフェースだけを見る。approval の型は参照しない
if (!monthClosureQuery.acceptsChanges(employeeId, month)) { ... }
```

**`workrule → approval` の依存は作らない。**
実装は `approval/infrastructure` に置く
（[アーキテクチャ設計書](../00_共通/アーキテクチャ設計書.md)）。

---

## 4. 実装上の注意

| # | 内容 |
| --- | --- |
| 1 | 労働時間制度ごとのレスポンス組み立ては、ドメインの `sealed interface` に対する `switch` で行う。制度を追加したときにコンパイルエラーで気づけるようにする |
| 2 | 割増率の JSON 出力は `BigDecimal` を文字列としてシリアライズする（`@JsonFormat(shape = STRING)`） |
| 3 | カレンダーの一括設定は件数が多いため、1 件ずつ INSERT せず `INSERT ... ON CONFLICT DO UPDATE` でまとめる |
| 4 | 一括設定の警告検査は、登録後の状態に対して行う。登録前のカレンダーで検査すると結果が変わる |
| 5 | 改定は「現行版を閉じる UPDATE」と「新版の INSERT」を **同一トランザクション**で行う。分けると版に隙間ができ、その間の勤怠が計算不能になる |

---

## 5. 未決事項

| # | 内容 | 判断の時期 |
| --- | --- | --- |
| 1 | 祝日の自動取込 API を設けるか（内閣府 CSV の URL を指定して取り込む） | M1-a の実装時 |
| 2 | 就業規則の改定時に、影響を受ける未締めの月を自動で再計算するか | 05_申請承認と締め の設計時 |
| 3 | 警告の検査範囲を改定日から 12 か月としているが、これで十分か | 運用開始後に見直す |
