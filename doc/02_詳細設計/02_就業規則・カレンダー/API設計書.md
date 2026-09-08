# 就業規則・カレンダー API設計書

| 項目 | 内容 |
| --- | --- |
| 文書番号 | KNT-DES-203 |
| 版 | 0.3 |
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
| `urn:kintai:error:monthly-basis-changed-mid-month` | 422 | 月の途中の改定が、月次清算に効く値（所定労働時間・法定労働時間・労働時間制度）を変えている（2.2） |
| `urn:kintai:error:invalid-work-rule-request` | 422 | 就業規則の指定が不正（休憩が拘束時間を超える・コアタイムの長さが 0・割増率が読めない 等）。**ドメインが投げる `IllegalArgumentException` をここへ写す。素通りさせると理由の載らない 500 になる**（落とし穴 105）|
| `urn:kintai:error:abolished-work-rule-series` | 422 | 廃止済みの系列を改定・適用しようとした |

---

## 2. 就業規則

### 2.0 `GET /api/work-rules` と `POST /api/work-rules`

一覧は**系列だけ**を返す。版の履歴は含めない。
含めると、系列が増えるほど全系列の全版を毎回読むことになる。

```json
[
  { "seriesId": "...", "name": "標準勤務", "version": 0 }
]
```

新規登録は**系列と初版を同時に作る**。版を持たない系列は
「適用できるが規則が引けない」状態であり、作れてはいけない。

```json
{
  "name": "標準勤務",
  "validFrom": "2026-04-01",
  "system": { "fixedTime": { "scheduledStart": "09:00", "scheduledEnd": "18:00",
                             "scheduledBreakMinutes": 60 } }
}
```

| 項目 | 既定 | 備考 |
| --- | --- | --- |
| `statutoryDailyMinutes` | 480 | 法定 8 時間。**超える値は 422** |
| `statutoryWeeklyMinutes` | 2400 | 法定 40 時間。同上 |
| `nightWindow` | `STANDARD` | `STANDARD`（22:00–05:00）か `DESIGNATED_AREA`（23:00–06:00）のみ |
| `premiumRates` | 法定下限 | 下回ると 422 |

> **`system` は `fixedTime` か `flextime` の<strong>どちらか一方だけ</strong>。**
> 両方でも 0 個でも 422 で拒む。平坦に受けると
> 「FLEX なのに始業時刻がある」形を作れてしまい、
> DB の CHECK 制約が禁じた状態を API が再現する。

> **締め済みの月を拒まない。**
> 新しい系列はまだ誰にも適用されていないので、確定済みの勤怠を 1 件も動かさない。
> 拒むのは**適用**（2.3）の側である。ここで拒むと、
> 過去に遡って規則を整備することが永久にできなくなる。

**版は 0 から始まる。** 月次勤怠は 1 から始めると決めた（CLAUDE.md 落とし穴 57）が、
あちらの危険は「行が無い月」を画面が版 0 として握れることにある。
**系列は取得しないと識別子が分からない**ので、存在しない系列の版を握ることがなく、
存在しなければ改定は版を見る前に 404 で終わる。

#### 2.0a 法定労働時間は入力ではない

**`statutoryDailyMinutes` / `statutoryWeeklyMinutes` は受け取らない。**

1 日 8 時間・1 週 40 時間は<strong>法が決める定数</strong>であり（労基法 32 条）、
会社が設定するものではない。会社が定めるのは<strong>所定</strong>労働時間のほうで、
それは `system`（`fixedTime` / `flextime`）が持っている。

受け取っていた頃は、40 時間未満を指定した規則を登録できた。
ドメインは「40 時間以下」しか課さないので通り、
ところが `monthly_settlements_statutory_limit_check` と
`weekly_overtimes_calculation_check` は式に 2400 を直書きしている。
**その社員の月次清算だけが永久に保存できず、理由の載らない 500 になった**
（IT-API-46）。

> **検査されていない入力は、設定できるという見かけだけを増やす**（落とし穴 126）。
> 送られてきても無視する（IT-WR-44）。
>
> ドメインの `WorkRule` は引数として受け取り続ける。
> 計算が規則の値を読んでいるのか、同じ定数を偶然使っているのかを
> テストが区別できなくなるからである（落とし穴 55）。
>
> **廃止した観点**（表に残すと生成器が現役として拾う・落とし穴 100）
> - ~~`IT-WR-30` 法定労働時間が 0 なら 400~~ → 入力そのものが無くなった。`IT-WR-44` が引き継ぐ

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

> **閉じてから入れるまでを 1 つの操作にする。**
> 保存を 2 回に分けて呼ぶだけでは足りない。
> **JPA は 1 回のフラッシュで INSERT を UPDATE より先に実行する**ので、
> 「閉じてから入れた」つもりでも DB には「入れてから閉じた」順で届き、
> `work_rules_no_overlap` に弾かれる。
> 順序は永続化の都合なので `WorkRuleRepository.revise` に閉じ込める
> （CLAUDE.md 落とし穴 73）。
**社員の適用行は一切書き換えない。** 適用は系列を指しているため、
新しい版は `validFrom` 以降の日付で自動的に選ばれる。

#### 月の途中の改定は、月次清算に効く値を変えないものに限る

**月中の改定そのものは禁じない。**
日次計算は日ごとに版を引くので、深夜帯や割増率を月の途中から変えるのは正しく動く
（IT-SCN-09 が通しで確かめている）。一律に禁じると、
深夜帯の誤りを月の途中で直せなくなる。

禁じるのは<strong>所定労働時間・法定労働時間・労働時間制度</strong>を
月の途中から変えることである。
月次清算は 1 か月を 1 つの版で計算するので
（[04 の 2.0.1](../04_勤怠_月次清算/ドメインモデル設計書.md)）、
これらが月中で割れると
**所定総労働時間も不足時間も法定総枠も片方の版の値だけで求まる。**

> フレックスの清算期間は労使協定が定めた起算日から 1 か月であり（労基法 32 条の 3）、
> その途中で所定を差し替えること自体が制度の前提に反する。

**ここで拒まないと、月次清算の側が拒むことになる。**
そうなるとその月は提出も承認も締めもできず、
規則を戻す以外に出口の無い月が残る（CLAUDE.md 落とし穴 26・93）。

**新規登録（2.0）には課さない。**
新しい系列はまだ誰にも適用されていないので、確定済みの勤怠を動かさない。
月中入社の社員のために入社日から始める使い方もある。

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
| `422 monthly-basis-changed-mid-month` | `validFrom` が月初日ではなく、所定労働時間・法定労働時間・労働時間制度のいずれかが変わっている |
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

### 2.2.1 `GET /api/work-rules/{seriesId}/effective`

`date` に有効な版を 1 つ返す（2.1 の `revisions` の要素と同じ形）。

| 応答 | 条件 |
| --- | --- |
| `200 OK` | その日に有効な版がある |
| `404 resource-not-found` | **系列が無い**（識別子の誤り） |
| `404 work-rule-version-not-effective` | 系列はあるが、その日に有効な版が無い |

> **この 2 つを同じエラーにしない。**
> 前者は綴りの誤りで、後者は**まだ効いていない**という業務上の事実である。
> 年度の途中で新設した系列は、それ以前の日に版を持たないのが正常であり
> （CLAUDE.md 落とし穴 131）、利用者がすることがまったく違う。

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
    { "date": "2026-05-01", "dayType": "WORKDAY" },
    { "date": "2026-05-02", "dayType": "NON_LEGAL_HOLIDAY", "name": "所定休日" },
    { "date": "2026-05-03", "dayType": "LEGAL_HOLIDAY", "name": "憲法記念日" }
  ],
  "workdayCount": 21
}
```

> **名称の無い日は `name` の項目ごと省く。**
> `null` を置くと「名前が無い」と「名前が空」が同じ値になる（落とし穴 76）。
> 名称は書き込み（`PUT /api/calendars/{date}` と一括登録）が受け取るので、
> **読み出せないと、人事が登録した祝日名がどこにも出ない。**

**期間は半開区間で受ける。** 内部の `DateRange` と同じ流儀にそろえ、
境界で 1 日ずれる不具合を作らない。
画面が「5 月」を表示するときは `from=2026-05-01&toExclusive=2026-06-01` を送る。

**未登録の日も `WORKDAY` として配列に含めて返す。**
「配列に無い日は所定労働日」という暗黙のルールをクライアントに持たせない。

`workdayCount` を含めるのは、フレックスの所定総労働時間を画面に表示するためである。

### 3.2 `POST /api/calendars/bulk`

年度初めにまとめて登録するための一括設定。

**この操作は給与連携（BR-18）の前提でもある。**
割増賃金の基礎額の分母は**年度の全日が登録されていること**を要求するので、
1 日ずつの API しか無いと運用で満たせない
（[給与連携 ドメインモデル設計書 4.3](../07_給与連携/ドメインモデル設計書.md)）。

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
| `409 fiscal-year-used-by-payroll` | **給与連携の出力に使った分母が動く**（下記）|
| `422 invalid-calendar-request` | 期間が逆・期間が 1,096 日超・同じ曜日の規則が 2 つ・同じ日の個別指定が 2 つ・個別指定が期間の外 |

#### 利用者が送る値はここで検査する

`DateRange` の compact constructor や `Collectors.toMap` に任せると、
期間が逆でも曜日が重複していても `IllegalArgumentException` /
`IllegalStateException` になり、**理由の載らない 500** が返る。
compact constructor は最後の防波堤であって、業務エラーの窓口ではない（落とし穴 105）。

| 検査 | なぜ |
| --- | --- |
| `from < toExclusive` | 逆だと 500 になる |
| 期間は 1,096 日（3 年）まで | 上限が無いと 1000 年ぶん・36 万行の登録を 1 トランザクションで要求できる |
| 同じ曜日の規則が 2 つない | **後勝ちで畳まない。** どちらを意図したのか決められない |
| 同じ日の個別指定が 2 つない | 同上 |
| 個別指定が期間の内側にある | 黙って捨てると、**登録したつもりの祝日が入っていない**状態になる |

#### 出力に使った分母を動かす変更は拒む

割増賃金の基礎額の分母は**年度全体**の所定労働日数から決まる。
締め済みの月は上の判定で守られるが、**同じ年度のまだ来ていない月は変えられる。**

4 月分の給与を払ったあとに 12 月の休日を 1 日増やすと年度の分母が変わり、
**既に払った割増賃金の単価が事後的に足りなくなる。**
労基法 37 条の割増は下限なので、下回った月には差額の支払義務が残る。

**拒むのは「分母が動く変更」だけである。**
「出力したか」で拒むと、法定休日 → 所定休日の付け替えや名称の訂正のように
**所定労働日数を変えない訂正まで年度いっぱい止まり、**
BR-07 の 35% 判定の誤りを直せなくなる。
書き込んだあとに年間の所定を数え直し、記録した値と突き合わせる
（[07 ドメインモデル設計書 4.5](../07_給与連携/ドメインモデル設計書.md)）。

判定は `shared.domain.PayrollExportQuery`（実装は `payroll/infrastructure`）で行う。
`payroll` の型を直接見ると、依存図に無い `workrule → payroll` の辺が生まれる（ADR 0004）。
同じ判定を **3 つの経路すべて**に置く。

| 経路 | 理由 |
| --- | --- |
| `PUT /api/calendars/{date}` | 1 日ずつの変更 |
| `POST /api/calendars/bulk` | **端の 2 日だけを見ない。** 3 年度にまたがる期間で中間年度が素通りする |
| `POST /api/employees/{id}/work-rule-assignments` | **分母の入力はカレンダーだけではない。** 年間の所定は「所定労働日 × その日に適用されている規則の所定」なので、適用を変えても動く |

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
| 1 | ~~祝日の自動取込 API を設けるか~~ **解決済み。設けない。** 人事が `POST /api/calendars/bulk` で登録する（HTTP クライアントは依存にも入れていない）。内閣府の CSV は「国民の祝日」しか持たず、年末年始・創立記念日・所定休日は結局手で入れる | 完了 |
| 2 | ~~改定時に未締めの月を自動で再計算するか~~ **解決済み。自動では再計算しない。** 人事が `POST .../recalculation` で指示する。締め済みの月は改定そのものを拒み、月次清算に効く値の月中改定も拒む（2.2）| 完了 |
| 3 | ~~警告の検査範囲を改定日から 12 か月としているが十分か~~ **解決済み。12 か月のままとする。** 実質的な上限は窓の長さではなく**カレンダーの登録範囲**である（未登録の月は飛ばす・IT-WR-46）。伸ばしても登録の先には出ないので、意味を持つのは登録済みの範囲だけになる | 完了 |

---

## 4. 結合テストの観点

| ID | 観点 | 期待 |
| --- | --- | --- |
| `IT-WR-24` | **登録すると系列と初版ができる** | 版の履歴が 1 件。使わないほうの制度のキーは出ない |
| `IT-WR-25` | 人事でない利用者の登録 | 403 |
| `IT-WR-26` | **所定が法定 8 時間を超える** | 422。人事が登録画面から踏める誤りなので 500 にしない |
| `IT-WR-27` | 制度を 2 つとも指定 | 422 `invalid-work-rule-request` |
| `IT-WR-28` | 制度を 1 つも指定しない | 422 |
| `IT-WR-29` | 名称が空 | 400 |
| `IT-WR-44` | **法定労働時間を送っても無視される** | 常に法定の 8 時間・40 時間で登録される（2.0a）|
| `IT-WR-31` | **改定すると現行版が閉じて新しい版が増える** | 既存の版は書き換わらない。閉じる位置は改定日（半開区間）|
| `IT-WR-32` | 版が一致しない改定 | 409 `optimistic-lock-failure` |
| `IT-WR-33` | **版が一致しないとき、行も版も増えない** | 先に版を進めるので、競合時に片方だけ書かれた状態が残らない |
| `IT-WR-34` | 現行版より前の日付への改定 | 409 `overlapping-period` |
| `IT-WR-35` | 存在しない系列の改定 | 404 |
| `IT-WR-42` | **月中の改定で所定労働時間を変える** | 422 `monthly-basis-changed-mid-month` |
| `IT-WR-43` | **所定を変えない月中の改定**（深夜帯だけ） | 201。一律に拒まない |
| `IT-WR-45` | **その日に有効な版が無い系列の適用** | 422 `no-effective-work-rule-version`。隙間を作らせない |
| `IT-WR-46` | **カレンダー未登録の月の所定総の警告** | 出さない。未登録の日を所定労働日として数えると必ず総枠を超え、警告が雑音になる |
| `IT-WR-47` | **休憩が拘束時間を超える登録** | 422 `invalid-work-rule-request`。**500 にしない。** 画面の休憩は上限の無い数値入力なので実際に送れる（落とし穴 105）|
| `IT-WR-48` | **コアタイムの開始と終了が同じ登録** | 422。プルダウンで同じ値を選べる |
| `IT-WR-36` | 一覧 | 版の履歴を含めない |
| `IT-WR-37` | 指定日に有効な版 | その日を含む版が返る |
| `IT-WR-38` | **版が始まる前の日付** | 404 `work-rule-version-not-effective`。**系列が無い場合とは別の型** |
| `IT-WR-39` | 規則の無い在籍者 | 適用済みの社員は現れない。社員番号も氏名も返さない |
| `IT-WR-40` | 本人が自分の適用履歴を見る | 見られる。自分の労働条件そのものである |
| `IT-WR-41` | 一般社員が他人の適用履歴を見る | 403 |
| `IT-CAL-13` | **未登録の日** | `WORKDAY` として配列に含まれる。「配列に無い日は所定労働日」を持たせない |
| `IT-CAL-14` | 登録した暦日区分 | 反映され、`workdayCount` から外れる |
| `IT-CAL-15` | 期間の逆転 | 422 `invalid-period`（500 にしない）|
| `IT-CAL-16` | **名称の無い日** | `name` の項目そのものが無い。空文字だと「名前が無い」と「名前が空」が同じ値になる |
