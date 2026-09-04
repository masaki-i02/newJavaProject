# 年次有給休暇 API設計書

| 項目 | 内容 |
| --- | --- |
| 文書番号 | KNT-DES-603 |
| 版 | 0.1 |
| 対象パッケージ | `jp.co.sample.kintai.leave.presentation` |
| 関連文書 | [ドメインモデル設計書](ドメインモデル設計書.md) / [DB設計書](DB設計書.md) / [設計規約チェックリスト](../00_共通/設計規約チェックリスト.md) |

共通仕様は [社員・組織 API設計書 1 章](../01_社員・組織/API設計書.md#1-共通仕様) に従う。

---

## 1. エンドポイント一覧

| メソッド | パス | 概要 | 必要ロール |
| --- | --- | --- | --- |
| `GET` | `/api/employees/{id}/paid-leave` | 残日数・付与の内訳・年 5 日の状況 | 本人 / 承認者 / `HR` / `ADMIN` |
| `GET` | `/api/employees/{id}/paid-leave-requests` | 申請の一覧 | 本人 / 承認者 / `HR` / `ADMIN` |
| `POST` | `/api/employees/{id}/paid-leave-requests` | 取得の申請 | **本人のみ** |
| `GET` | `/api/paid-leave-requests/{id}` | 申請 1 件 | 本人 / 承認者 / `HR` / `ADMIN` |
| `POST` | `/api/paid-leave-requests/{id}/approval` | 承認 | BR-11 の承認者 |
| `POST` | `/api/paid-leave-requests/{id}/rejection` | 却下 | BR-11 の承認者 |
| `POST` | `/api/paid-leave-requests/{id}/cancellation` | 取下げ | **本人のみ** |
| `POST` | `/api/paid-leave-requests/{id}/revocation` | **取得日の当日以降の取消** | `HR` |
| `GET` | `/api/paid-leave-requests/pending-approval` | 承認待ちの一覧 | 承認者 / `HR` |
| `POST` | `/api/paid-leave-grants` | 付与の実行（基準日まで） | `HR` |
| `POST` | `/api/employees/{id}/paid-leave-grants/{grantedOn}/reassessment` | 出勤率の再判定 | `HR` |
| `GET` | `/api/paid-leave/obligations` | 年 5 日が未達の社員（BR-17） | 承認者 / `HR` |

### 1.1 楽観ロック

`version` を必須で受け取るのは、**申請の状態を変える 3 つ**である。

| 操作 | `version` |
| --- | --- |
| 承認 / 却下 / 取下げ | **必須** |
| 申請 | 不要（新規作成） |
| 付与の実行 / 再判定 | 不要（`(employee_id, grant_index)` の一意制約が二重実行を防ぐ） |

承認待ちの**一覧には `version` を載せない。**
一覧で行ごとに引くと社員数ぶんの問い合わせが増え、
版が要るのは実際に決裁する 1 件だけである
（[社員・組織 API設計書 3.12](../01_社員・組織/API設計書.md)）。

### 1.2 このコンテキストのエラー型

| `type` | HTTP | 発生条件 |
| --- | --- | --- |
| `urn:kintai:error:insufficient-paid-leave` | 422 | **残日数が足りない**（未処理の申請を含めて判定） |
| `urn:kintai:error:not-a-workday` | 422 | 取得日が所定労働日でない |
| `urn:kintai:error:leave-date-not-in-service` | 422 | 取得日に在籍していない（入社前・退職後） |
| `urn:kintai:error:duplicate-leave-request` | 409 | 同じ日に有効な申請が既にある |
| `urn:kintai:error:month-not-editable` | 409 | 対象月が**承認済み**（締めてはいない） |
| `urn:kintai:error:month-already-closed` | 409 | 対象月が締め済み |
| `urn:kintai:error:leave-not-cancelable` | 409 | 取得日の当日以降で取り消そうとした |
| `urn:kintai:error:invalid-transition` | 409 | 既に決裁済み・取下げ済み |
| `urn:kintai:error:not-approver` | 403 | 実行者が BR-11 の承認者でない |
| `urn:kintai:error:not-the-requester` | 403 | 本人以外が申請・取下げをしようとした |
| `urn:kintai:error:grant-already-granted` | 409 | 付与済みの付与を再判定しようとした |
| `urn:kintai:error:grant-not-yet-issued` | 409 | **取得日に有効な付与がまだ実体化していない**（承認時） |
| `urn:kintai:error:self-approval` | 403 | **自分の申請を自分で承認・却下しようとした**（BR-11） |

**`month-already-closed` と `month-not-editable` を分ける。**
承認済みは承認を取り消せば直せるので、利用者への案内がまったく違う
（[申請承認 API設計書 3.1](../05_申請承認と締め/API設計書.md)）。

**`self-approval` を `not-approver` にまとめない。**
`ApproverPolicy` は本人を承認者から外すので、まとめると
**自己承認の禁止を消してもテストが 1 件も落ちない**
（[CLAUDE.md 落とし穴 58](../../../CLAUDE.md)）。
集約が承認者の判定より先に自己承認を弾く
（[ドメインモデル設計書 4.2](ドメインモデル設計書.md)）。型は 05 とそろえる。

---

## 2. 残日数の参照

### 2.1 `GET /api/employees/{id}/paid-leave`

| パラメータ | 既定値 | 内容 |
| --- | --- | --- |
| `asOf` | **`application` 層が `Clock` から解決した当日** | 残日数の基準日 |

```json
{
  "employeeId": "0195c000-0000-7000-8000-000000000001",
  "asOf": "2026-09-04",
  "remainingDays": 8,
  "availableDays": 6,
  "grants": [
    {
      "grantedOn": "2024-10-01",
      "expiresOn": "2026-10-01",
      "granted": true,
      "days": 10,
      "usedDays": 10,
      "remainingDays": 0,
      "attendanceRate": { "totalWorkingDays": 122, "attendedDays": 120 }
    },
    {
      "grantedOn": "2025-10-01",
      "expiresOn": "2027-10-01",
      "granted": true,
      "days": 11,
      "usedDays": 3,
      "remainingDays": 8,
      "attendanceRate": { "totalWorkingDays": 245, "attendedDays": 240 }
    }
  ],
  "obligations": [
    {
      "grantedOn": "2025-10-01",
      "deadline": "2026-09-30",
      "requiredDays": 5,
      "takenDays": 3,
      "shortfallDays": 2
    }
  ]
}
```

出勤率が 8 割に満たなかった年は `"granted": false` の要素になり、
`days` / `usedDays` / `remainingDays` を持たない（BR-14）。
**省くのはこの 3 項目だけで、`@JsonInclude` は項目ごとに付ける。**
record 全体に付けると `attendanceRate` の内訳まで消える
（[CLAUDE.md 落とし穴 76](../../../CLAUDE.md)）。
**要素そのものを省かない。** 省くと、付与処理をしていないのか、
法どおり不付与だったのかが読み取れない。

未処理の申請が 2 件あるので `availableDays` は `remainingDays` より 2 少ない。

| 項目 | 内容 |
| --- | --- |
| `remainingDays` | その日に有効な付与の残の合計 |
| `availableDays` | **未処理の申請を仮に配分したあと、なお残っている日数。** 件数の引き算ではない（[ドメインモデル設計書 3.4](ドメインモデル設計書.md)） |
| `expiresOn` | **半開区間の上限。この日には既に失効している**（BR-15） |
| `deadline` | **閉区間の最終日**（`付与日 + 1 年 − 1 日`）。利用者に示す期限日 |

**`expiresOn` と `deadline` で区間の扱いが違うことを明記する。**
`expiresOn` は内部の区間の上限をそのまま出したもの、
`deadline` は「いつまでに取ればよいか」という利用者向けの日付である。
どちらも `2027-10-01` / `2026-09-30` のように 1 日ずれるので、
**同じ名前にすると必ず取り違える**（[設計規約チェックリスト 2](../00_共通/設計規約チェックリスト.md)）。

**`remainingDays` と `availableDays` を両方返す。**
片方だけだと「残 3 日と表示されたのに申請が拒否される」か、
「残日数が実際より少なく見える」のどちらかになる。

**社員番号・氏名・部署を返さない。** `employee` が所有する概念である
（[設計規約チェックリスト 3](../00_共通/設計規約チェックリスト.md)）。

**閲覧範囲は `EmployeeVisibility` で判定する。**
「配下部署か」は組織と基準日に依存するので、Spring Security の設定には置けない。

### 2.2 `GET /api/paid-leave/obligations`（BR-17）

| パラメータ | 既定値 | 内容 |
| --- | --- | --- |
| `asOf` | 当日 | この日を含む義務期間を対象にする |
| `onlyShortfall` | `true` | 未達の社員だけに絞る |

```json
{
  "asOf": "2026-09-04",
  "items": [
    {
      "employeeId": "...",
      "grantedOn": "2025-10-01",
      "deadline": "2026-09-30",
      "takenDays": 3,
      "shortfallDays": 2,
      "remainingDaysUntilDeadline": 26
    }
  ]
}
```

**配列を裸で返さずオブジェクトで包む。**

**閲覧範囲で絞る。** `shared.domain.EmployeeVisibility` が返す社員だけを対象にする。
絞らないと、一般の承認者が**配下でない社員の年休の取得状況**を見られる（要件 4.1）。
対応するクエリとインデックスは [DB設計書 4.2.1](DB設計書.md) に置く。

**未達であっても、この API 以外は何も止めない**（BR-17）。
使用者による時季指定は実装しない。人事が本人と調整するための材料を出すだけである。

---

## 3. 取得の申請

### 3.1 `POST /api/employees/{id}/paid-leave-requests`

```json
{
  "leaveDate": "2026-10-15",
  "reason": "私用のため"
}
```

`reason` は任意である。時季指定は労働者の権利であり（39 条 5 項）、
必須にすると「理由が不十分だから却下する」という運用を招く
（[ドメインモデル設計書 4.1](ドメインモデル設計書.md)）。

| 応答 | 条件 |
| --- | --- |
| `201 Created` | 申請成功 |
| `403 not-the-requester` | **本人以外が申請した**（人事の代理申請も認めない） |
| `409 duplicate-leave-request` | 同じ日に未処理・承認済みの申請がある |
| `409 month-not-editable` | 対象月が承認済み |
| `409 month-already-closed` | 対象月が締め済み |
| `422 insufficient-paid-leave` | 残日数が足りない |
| `422 not-a-workday` | 所定休日・法定休日を指定した |
| `422 leave-date-not-in-service` | 取得日に在籍していない |

```json
{
  "type": "urn:kintai:error:insufficient-paid-leave",
  "title": "年次有給休暇の残日数が足りません",
  "status": 422,
  "detail": "2026-10-15 に有効な残日数は 0 日です（未処理の申請 2 件を含む）"
}
```

**判定は「その取得日に有効な付与」で行う。**
合計の残日数が足りていても、その日に有効な付与が無ければ取得できない（BR-15）。
`detail` に日付を含めるのは、合計だけを見た利用者が理由を理解できないためである。

**未処理の申請も差し引く。** 承認済みだけを引くと、
残 1 日に対して 2 件の申請が同時に通る（[ドメインモデル設計書 3.4](ドメインモデル設計書.md)）。

**未到来の付与日をまたぐ日程も申請できる。**
付与の行は到来したぶんしか作られないので、
そのままだと次の付与日の直後の日程を**付与日が来るまで誰も申請できない。**
申請の判定では `GrantSchedule` から到来予定の付与を仮に組み入れる
（[ドメインモデル設計書 3.3](ドメインモデル設計書.md)）。
配分が確定するのは承認の時点なので、そこでは実体化した付与だけを使う。

**代理申請を認めない。** 訂正申請と同じ判断である。
時季指定は本人の意思表示であり、人事でも代わりには出せない。

### 3.2 `POST /api/paid-leave-requests/{id}/approval`

```json
{ "version": 1 }
```

承認すると 4 つの処理が 1 トランザクションで実行される
（[ドメインモデル設計書 4.3](ドメインモデル設計書.md)）。

| # | 処理 | 対象 |
| --- | --- | --- |
| 1 | 先入先出で付与へ配分し、`APPROVED` にする | `leave` |
| 2 | **月次清算を再計算する**（所定総から年休の日を除く） | `attendance` |
| 3 | **提出済みなら月次勤怠を下書きへ戻す**（`REVERT_BY_LEAVE`） | `approval` |
| 4 | 遷移を証跡に記録する | `leave` |

```json
{
  "requestId": "...",
  "status": "APPROVED",
  "leaveDate": "2026-10-15",
  "grantedOn": "2026-04-01",
  "version": 2,
  "settlement": { /* 再計算後の月次清算 */ },
  "monthlyAttendanceStatus": "DRAFT"
}
```

**`grantedOn` を返す。** どの付与から消化したかは失効時期に直結する。
「残 3 日」とだけ示されても、それが今月末に失効するのかは分からない。

**2 と 3 の結果を応答に含める。** 提出済みだった月が下書きに戻ることを伝えないと、
再提出が忘れられる（訂正の承認と同じ）。

| 応答 | 条件 |
| --- | --- |
| `200 OK` | 承認成功 |
| `403 self-approval` | **自分の申請を自分で承認した**（BR-11）。集約が承認者の判定より先に弾く |
| `403 not-approver` | 実行者が `leaveDate` の月の BR-11 の承認者でない |
| `409 invalid-transition` | 既に決裁済み・取下げ済み |
| `409 optimistic-lock-failure` | `version` が一致しない |
| `409 month-not-editable` | **対象月が承認済み**（まだ締めてはいない） |
| `409 month-already-closed` | 対象月が締め済み |
| `409 grant-not-yet-issued` | **取得日に有効な付与がまだ実体化していない**。付与日を案内する |
| `422 insufficient-paid-leave` | **承認の時点で残日数が無くなっていた** |

**`month-not-editable` を落とさない。** 月次勤怠が承認済みの月で年休を承認すると、
月次清算だけが変わり、#3（下書きへ戻す）は「提出済みなら」なので**戻らない。**
承認者が承認した内容と、締めで確定する内容が黙って食い違う
（[ドメインモデル設計書 4.2](ドメインモデル設計書.md)）。

**承認の時点でも残日数を確かめる。**
申請から承認までの間に、別の申請が先に承認されることがある。
申請時の検査だけに頼ると、**付与日数を超えて承認できてしまう**
（残日数の上限は DB では守れない・[DB設計書 5.1](DB設計書.md)）。

### 3.3 `POST /api/paid-leave-requests/{id}/rejection`

```json
{ "version": 1, "comment": "その週は繁忙のため別日でお願いします" }
```

`comment` は必須である（`400` / `paid_leave_requests_state_check`）。
理由なしの却下は、本人が次に何をすればよいか分からない。

| 応答 | 条件 |
| --- | --- |
| `200 OK` | 却下成功 |
| `400 validation-failed` | `comment` が空・空白のみ |
| `403 self-approval` | 自分の申請を自分で却下した |
| `403 not-approver` | 実行者が `leaveDate` の月の BR-11 の承認者でない |
| `409 invalid-transition` | 既に決裁済み・取下げ済み |
| `409 optimistic-lock-failure` | `version` が一致しない |

**締め済みの月でも却下できる。** 却下は残日数も月次清算も動かさないので、
拒否すると**どの状態にも遷移できない申請**が残るだけである
（[ドメインモデル設計書 4.2](ドメインモデル設計書.md)）。

### 3.4 `POST /api/paid-leave-requests/{id}/cancellation`

```json
{ "version": 2 }
```

| 応答 | 条件 |
| --- | --- |
| `200 OK` | 取下げ成功。承認済みだった場合は残日数が戻る |
| `403 not-the-requester` | 本人以外が取り下げた |
| `409 leave-not-cancelable` | **承認済みで、取得日の当日以降**。人事の取消（3.5）へ案内する |
| `409 month-already-closed` | 対象月が締め済み |
| `409 optimistic-lock-failure` | `version` が一致しない |
| `409 invalid-transition` | 既に却下済み・取下げ済み |

**申請中（未決裁）はいつでも取り下げられる**（BR-16）。
期限を設けると、承認者が決裁しないまま取得日と月末が過ぎた申請が
**承認も却下も取下げもできなくなる**（[ドメインモデル設計書 4.2](ドメインモデル設計書.md)）。

**承認済みは取得日の前日まで**（BR-16）。当日以降は実績が確定しているので、
本人ではなく人事が理由を付けて取り消す（3.5）。

**承認済みの取下げも本人が行う。** 承認者の同意を必須にすると、
承認者が不在の間、本人が予定を変えられなくなる（落とし穴 26）。
残日数が戻るだけで、誰かに不利益は生じない。

**承認済みを取り下げると月次清算を再計算する。** 承認と対称である。
再計算しないと、取り消した年休の日が所定総から除かれたままになり、
**不足時間が 8 時間ぶん過少に出る。**

### 3.5 `POST /api/paid-leave-requests/{id}/revocation`（人事による取消）

```json
{ "version": 2, "comment": "予定を変更して出勤したため" }
```

取得日の**当日以降**に、承認済みの年休を人事が取り消す（BR-16）。

| 応答 | 条件 |
| --- | --- |
| `200 OK` | 取消成功。残日数が戻り、月次清算を計算し直す |
| `400 validation-failed` | `comment` が空・空白のみ |
| `403 forbidden` | 実行者が `HR` でない |
| `409 invalid-transition` | 承認済み以外を取り消そうとした |
| `409 optimistic-lock-failure` | `version` が一致しない |
| `409 month-already-closed` | 対象月が締め済み |
| `409 month-not-editable` | 対象月が承認済み |

**なぜ人事なのか。** 予定を変えて出勤した社員は、
取り消す手段が無いと**年休 1 日を消費したままその日も働く**ことになる。
BR-16 の第 1 版はこれを訂正申請（BR-09）へ委ねていたが、
**訂正申請が動かせるのは打刻だけ**で、年休の取消はできなかった。

本人に許さないのは、**実績が確定した日を本人が動かせてはいけない**ためである。
証跡は `REVOKE` として残し、本人の取下げ（`CANCEL`）と区別する。

**承認済みの取下げも本人が行う。** 承認者の同意を必須にすると、
承認者が不在の間、本人が予定を変えられなくなる（落とし穴 26）。
残日数が戻るだけで、誰かに不利益は生じない。

**承認済みを取り下げると月次清算を再計算する。** 承認と対称である。
再計算しないと、取り消した年休の日が所定総から除かれたままになり、
**不足時間が 8 時間ぶん過少に出る。**

---

## 4. 付与

### 4.1 `POST /api/paid-leave-grants`

```json
{ "asOf": "2026-10-01" }
```

`asOf` を省略すると、`application` 層が `Clock` から解決した当日になる。

```json
{
  "asOf": "2026-10-01",
  "granted": [
    { "employeeId": "...", "grantedOn": "2026-10-01", "days": 11 }
  ],
  "withheld": [
    { "employeeId": "...", "grantedOn": "2026-10-01",
      "attendanceRate": { "totalWorkingDays": 245, "attendedDays": 180 } }
  ],
  "skipped": [
    { "employeeId": "...", "grantedOn": "2026-10-01", "reason": "already-granted" }
  ]
}
```

| 決定 | 理由 |
| --- | --- |
| **冪等**。既に処理済みの付与は `skipped` に入る | 同じ日に 2 回実行しても二重に付与しない |
| **到来済みで未処理のものをすべて作る** | バッチが動かなかった日があっても次の実行で追いつく（落とし穴 26） |
| **付与日に在籍している社員だけを対象にする** | 退職者を除かないと毎年 20 日が積み上がる。退職者の算定期間は全労働日 0 になるので、**8 割の判定は必ず通ってしまう**（ドメインモデル設計書 2.5） |
| **社員ごとの事情は結果へ、依頼そのものの不備は例外へ** | `HR` でない実行者は `403`。8 割未達は `withheld` に入れて処理を続ける |
| 日次バッチ（`@Scheduled`）は**このユースケースをそのまま呼ぶ** | 手順を 2 か所に書かない（落とし穴 67） |

**一括操作の失敗を 1 種類にしない**（[申請承認 API設計書 2.7](../05_申請承認と締め/API設計書.md)）。
全員を `skipped` にすると、人事は自分に権限が無いことに気づけない。

**`withheld` を `skipped` と分ける。** 前者は法どおりの不付与、
後者は既に処理済みという運用上の事実であり、人事が取るべき行動が違う。

**社員ごとに別トランザクションで処理する。**
1 人の計算が失敗しても他の 99 人の付与が巻き戻らないようにする。
`@Transactional` は別クラス（`PaidLeaveGrantExecutor`）に置く。
同じクラスから呼ぶと Spring のプロキシを通らない（落とし穴 59）。

### 4.2 `POST /api/employees/{id}/paid-leave-grants/{grantedOn}/reassessment`

出勤率を判定し直す。使う場面は 2 つある。

1. 訂正申請（BR-09）で欠勤が出勤に直った
2. **休業（労災・産前産後・育児介護）を出勤扱いとして申告する**（BR-14）

```json
{ "deemedAttendedDays": 60, "deemedReason": "産前産後休業（2026-01-05〜2026-03-31）" }
```

```json
{
  "grantedOn": "2026-10-01",
  "granted": true,
  "days": 11,
  "attendanceRate": {
    "totalWorkingDays": 245,
    "attendedDays": 150,
    "deemedAttendedDays": 60,
    "deemedReason": "産前産後休業（2026-01-05〜2026-03-31）"
  }
}
```

**休業を記録する機能が無い間の措置である**（要件 1.1）。
記録が無いままだと休業日が欠勤に数えられ、
**産休・育休を取った社員はその年の付与が必ず 0 になる。**

**打刻を足して実績を整える運用は採らない。**
働いていない日の労働時間が一次証拠として残り、
日次勤怠と月次清算を通って割増賃金の計算に入る
（[ドメインモデル設計書 2.4](ドメインモデル設計書.md)）。

`deemedAttendedDays` を省略すると 0 として扱い、実績だけで判定し直す。
1 以上を渡すなら `deemedReason` は必須である（`400`）。

| 応答 | 条件 |
| --- | --- |
| `200 OK` | 再判定した（付与に変わった場合も、不付与のままの場合も） |
| `400 validation-failed` | `deemedAttendedDays` が 1 以上なのに `deemedReason` が空 |
| `403 forbidden` | 実行者が `HR` でない |
| `404 resource-not-found` | その日の付与が無い |
| `409 grant-already-granted` | **付与済みの付与を再判定しようとした** |
| `422 business-rule-violation` | 出勤日 + 出勤扱いが全労働日を超える |

**付与済みは対象外とする。** 一度発生した年休の権利を実績の訂正で消すのは
労働者に不利であり、消化済みなら辻褄も合わなくなる
（[ドメインモデル設計書 2.5](ドメインモデル設計書.md)）。

---

## 5. 実装上の注意

| # | 注意 |
| --- | --- |
| 1 | **依頼者は `Requester` を引数で渡す。** `SecurityContextHolder` を `application` 層から読まない（落とし穴 42）。日次バッチからも同じユースケースを呼ぶ |
| 2 | **`asOf` の既定値は `application` 層が `Clock` から解決する。** コントローラで `LocalDate.now()` を呼ばない（AR-09） |
| 3 | **時間は返さない。** 年休は 1 日単位であり（BR-16）、分単位の整数にする対象が無い |
| 4 | 承認・取下げは `attendance` / `approval` の `application` を呼ぶ。図にある辺に沿う（アーキテクチャ設計書 3.2） |
| 5 | **一括付与は社員ごとに別トランザクション。** 別クラスに置く（落とし穴 59） |

---

## 6. API の結合テストの観点

| ID | 観点 | 期待 | 参照 |
| --- | --- | --- | --- |
| IT-LV-31 | 残日数の参照 | 有効な付与の残の合計が返る | BR-15 |
| IT-LV-32 | **未処理の申請が `availableDays` から引かれる** | `remainingDays` > `availableDays` | BR-16 |
| IT-LV-33 | 他人の残日数を配下でない社員が見る | `403 forbidden` | 要件 4.1 |
| IT-LV-34 | 承認者が配下の社員の残日数を見る | `200 OK` | 要件 4.1 |
| IT-LV-35 | 年休の申請 | `201 Created` | BR-16 |
| IT-LV-36 | **他人の年休を代理で申請** | `403 not-the-requester` | BR-16 |
| IT-LV-37 | **`HR` が代理で申請** | `403 not-the-requester` | BR-16 |
| IT-LV-38 | 残日数を超える申請 | `422 insufficient-paid-leave` | BR-16 |
| IT-LV-39 | 所定休日を指定した申請 | `422 not-a-workday` | BR-16 / BR-07 |
| IT-LV-40 | **退職後の日を指定した申請** | `422 leave-date-not-in-service` | BR-16 |
| IT-LV-41 | 同じ日に 2 件目の申請 | `409 duplicate-leave-request` | BR-16 |
| IT-LV-42 | 締め済みの月への申請 | `409 month-already-closed` | BR-16 / BR-10 |
| IT-LV-43 | **承認済みの月への申請** | `409 month-not-editable` | BR-16 / BR-10 |
| IT-LV-44 | 承認者による承認 | `200 OK`・配分先が返る | BR-16 / BR-11 |
| IT-LV-45 | **自分の申請を自分で承認** | `403 self-approval`。`not-approver` にまとめない | BR-11 |
| IT-LV-46 | 承認者でない社員による承認 | `403 not-approver` | BR-11 |
| IT-LV-47 | **古い `version` での承認** | `409 optimistic-lock-failure` | BR-16 |
| IT-LV-48 | 決裁済みの申請を再び承認 | `409 invalid-transition` | BR-16 |
| IT-LV-49 | 却下（コメントあり） | `200 OK` | BR-16 |
| IT-LV-50 | コメント無しの却下 | `400 validation-failed` | BR-16 |
| IT-LV-51 | **承認済みを取得日の前日に取り下げる** | `200 OK`・残日数が戻る | BR-16 |
| IT-LV-52 | **承認済みを取得日の当日に取り下げる** | `409 leave-not-cancelable` | BR-16 |
| IT-LV-53 | 他人の申請を取り下げる | `403 not-the-requester` | BR-16 |
| IT-LV-54 | **提出済みの月の年休を承認すると下書きへ戻る** | `monthlyAttendanceStatus` が `DRAFT` | BR-10 / BR-16 |
| IT-LV-55 | **その差戻しが `REVERT_BY_LEAVE` として記録される** | 訂正による差戻しと区別できる | 要件 7 |
| IT-LV-56 | **承認すると月次清算の所定総が 8 時間減る** | 不足時間が立たない | BR-05 / BR-16 |
| IT-LV-57 | **取り下げると所定総が戻る** | 不足時間が 8 時間になる | BR-05 / BR-16 |
| IT-LV-58 | 付与の実行（`HR`） | `200 OK`・付与された社員が返る | BR-14 |
| IT-LV-59 | **同じ基準日で 2 回実行** | 2 回目は `skipped` に入り、二重付与されない | BR-14 |
| IT-LV-60 | **8 割未達の社員が `withheld` に入る** | `skipped` ではない | BR-14 |
| IT-LV-61 | `HR` でない社員による付与の実行 | `403 forbidden` | BR-14 |
| IT-LV-62 | **基準日を過去に指定すると、到来済みの未処理分がすべて作られる** | 2 回目の付与も作られる | BR-14 |
| IT-LV-63 | 不付与の付与を再判定して付与に変わる | `200 OK`・`granted` が `true` | BR-14 |
| IT-LV-64 | **付与済みの付与を再判定** | `409 grant-already-granted` | BR-14 |
| IT-LV-65 | 年 5 日の未達一覧 | 不足のある社員だけが返る | BR-17 |
| IT-LV-78 | **未達一覧に配下でない社員が現れない** | 閲覧範囲で絞られる | 要件 4.1 |
| IT-LV-66 | **年 5 日が未達でも提出・承認・締めが通る** | 止まらない | BR-17 |
| IT-LV-67 | 未認証での参照 | `401` | 要件 4 |
| IT-LV-79 | **承認済みの月の年休を承認** | `409 month-not-editable` | BR-10 / BR-16 |
| IT-LV-80 | **申請中は取得日を過ぎても本人が取り下げられる** | `200 OK` | BR-16 |
| IT-LV-81 | **締め済みの月でも却下できる** | `200 OK`。遷移できない申請を残さない | BR-16 |
| IT-LV-82 | **取得日の当日以降、人事が理由を付けて取り消す** | `200 OK`・残日数が戻る | BR-16 |
| IT-LV-83 | **人事以外が `revocation` を呼ぶ** | `403 forbidden` | BR-16 |
| IT-LV-84 | **人事の取消に理由が無い** | `400 validation-failed` | BR-16 |
| IT-LV-85 | **人事が取り消すと月次清算の所定総が戻る** | 不足時間が 8 時間になる | BR-05 / BR-16 |
| IT-LV-86 | **未到来の付与日以降の日程を申請できる** | `201 Created` | BR-16 |
| IT-LV-87 | **付与が実体化していない日を承認** | `409 grant-not-yet-issued` | BR-15 |
| IT-LV-88 | **古い `version` での却下** | `409 optimistic-lock-failure` | BR-16 |
| IT-LV-89 | **古い `version` での取下げ** | `409 optimistic-lock-failure` | BR-16 |
| IT-LV-90 | 出勤扱いの日数を申告して再判定すると付与に変わる | `200 OK`・`granted` が `true` | BR-14 |
| IT-LV-91 | **出勤扱いを申告して理由が無い** | `400 validation-failed` | BR-14 |
| IT-LV-92 | **退職者が付与の対象に入らない** | 付与の実行で `granted` にも `withheld` にも現れない | BR-14 |
| IT-LV-93 | **年休を取った月を提出できる** | `200 OK`。年休の日は「未確定」ではない | BR-10 / BR-16 |

---

## 7. 未決事項

| # | 内容 | 判断の時期 |
| --- | --- | --- |
| 1 | 日次バッチの実行時刻と、実行結果の通知先 | M2 の実装時 |
| 2 | 退職時に未消化の年休をどう扱うか（買上げは法定外。要件に無い） | 要件の改訂が要る |
| 3 | **年休の日に打刻があったことに気づく手段。** 人事の取消（3.5）で是正できるが、気づかなければ是正されない | M2 の実装時 |
