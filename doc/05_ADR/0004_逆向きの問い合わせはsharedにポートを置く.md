# ADR 0004: コンテキスト間の逆向きの問い合わせは、ポートを `shared` に置いて解決する

- **ステータス**: 採用
- **日付**: 2026-09-01
- **関連**: [ADR 0001](0001_パッケージを業務コンテキスト優先で分割する.md) / [アーキテクチャ設計書 4](../02_詳細設計/00_共通/アーキテクチャ設計書.md) / 要件定義書 BR-10

## 背景

コンテキスト間の依存は一方向に保っている（AR-06 / AR-07）。

```
approval ──> attendance ──> workrule ──> employee
                    └────────────────────> employee
すべて ──> shared
```

ところが **締め状態は逆向きに必要になる。** 締め済みの月に対して、

| 拒否したい操作 | 拒否する側 |
| --- | --- |
| 打刻の追加・日次の再計算 | `attendance` |
| 月次清算の再計算 | `attendance`（月次清算） |
| 会社カレンダーの変更・就業規則の遡及適用 | `workrule` |
| 遡及する異動・退職の登録 | `employee` |

締め状態を持っているのは `approval` である。
素直に問い合わせると `attendance → approval` や `workrule → approval` の辺ができ、**循環する。**

`PremiumType`（割増区分）も同じ形の問題を起こした。
`workrule` の `PremiumRates.multiplierFor(Set<PremiumType>)` と
`attendance` の区間分割の両方が使うため、どちらに置いても逆向きの辺ができる。

## 検討した選択肢

| 案 | 内容 | 利点 | 欠点 |
| --- | --- | --- | --- |
| A | 各コンテキストが締め状態を自分で持つ | 依存が増えない | **必ず食い違う。** 締めたのに片方が知らない状態が起きる |
| B | ポートを問い合わせる側（`attendance/domain`）に置き、`approval` が実装する | 依存が増えない（依存性逆転） | `workrule` も同じ判定が要る。**`workrule → attendance` という依存図に無い辺**が必要になる |
| C | イベント（`MonthClosed`）を発行し、各コンテキストが購読して自分の側に写す | 疎結合 | 結果整合になる。**締めた直後の打刻を取りこぼす**。個人開発の範囲で運用しきれない |
| D | **ポートを `shared.domain` に置き、実装を `approval/infrastructure` に置く** | **新しい辺が 1 本も増えない。** 4 つのコンテキストすべてが同じ形で使える | `shared` が育ちすぎる危険がある |

## 決定

**案 D を採る。**

```java
// shared/domain --- どのコンテキストからも見える
public interface MonthClosureQuery {
    boolean isClosed(EmployeeId employeeId, YearMonth month);
    boolean acceptsChanges(EmployeeId employeeId, YearMonth month);
}

// approval/infrastructure --- 締め状態を持つコンテキストが実装を提供する
class MonthClosureQueryAdapter implements MonthClosureQuery { ... }
```

**2 つ以上のコンテキストが使う語彙も同じ扱いにする。**

| 対象 | 置き場所 |
| --- | --- |
| `MonthClosureQuery` | `shared.domain`（実装は `approval`） |
| `PremiumType` | `shared.domain` |
| `EmployeeId` / `DateRange` / `TimeRange` | `shared.domain` |

## 根拠

案 A は「各コンテキストが独自に締め状態を持つと必ず食い違う」という一点で落ちる。
締めは**確定**を意味するので、食い違いが賃金の誤りに直結する。

案 B は一見よい。実際、第 1 版は
「`attendance/domain` に `MonthClosureQuery` を定義し、`approval` の実装を DI する」と書いていた。
**しかし締め状態を知りたいのは `attendance` だけではない。**
`workrule` も `employee` も同じ判定が要る。
`attendance` に置くと、そこへ向かう新しい辺が 2 本増える。
レビューで「規約と真逆」と指摘され、この誤りに気づいた。

案 C は本システムの規模に対して重い。
締めた直後に打刻が入る競合を、結果整合で正しく扱う設計は個人開発の範囲を超える。

案 D は、依存の規則（**どのコンテキストも `shared` に依存してよい**）を既に決めていたので、
**辺が 1 本も増えない。** これが決め手である。

## 結果

- `attendance` / `workrule` / `employee` が同じ 1 つのポートを使う。判定の実装は 1 か所
- 依存グラフは非巡回のまま。ArchUnit（AR-06 / AR-07）を緩めずに済んだ
- 判定に使う月は **勤務日（`work_date`）が属する月**と決めた。
  打刻時刻の月で判定すると、3/31 22:00 → 4/1 06:00 の退勤打刻が
  締め済みの 3 月に 4 月扱いで書き込めてしまう
- **`shared` が育ちすぎる危険は残る。** 歯止めとして次を決めた

> `shared.domain` に置いてよいのは、**2 つ以上のコンテキストが使う概念**だけである。
> 「共通で使えそう」では入れない。1 つのコンテキストしか使っていないものは、そのコンテキストに置く。

### 見直す条件

`shared.domain` の要素が 10 を超えたら、
「本当に 2 つ以上のコンテキストが使っているか」を洗い直す。
使っているのが 1 つだけになった要素は、そのコンテキストへ戻す。
