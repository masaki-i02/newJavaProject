# 勤怠管理システム

労働基準法にもとづく労働時間の集計と、月次の申請・承認・締めを扱う勤怠管理システム。

架空企業「株式会社サンプル」（従業員 100 名・単一事業所）の社内システムという設定で、
**業務ルールを型で表現し、ありえない状態をデータベース制約で作れなくする**ことを主題に据えた個人開発プロジェクト。

## 技術スタック

| 領域 | 技術 |
| --- | --- |
| バックエンド | Java 21 / Spring Boot 4.1 / Spring Data JPA / Spring Security |
| データベース | PostgreSQL 17 / Flyway |
| フロントエンド | React / TypeScript / Vite |
| ビルド | Gradle (Kotlin DSL) / npm |
| テスト | JUnit 5 / AssertJ / Testcontainers / ArchUnit / Vitest |

## ディレクトリ構成

```
.
├── doc/                    設計文書（工程ごとに番号付き）
│   ├── 01_要件定義/
│   ├── 02_詳細設計/
│   ├── 03_単体テスト/
│   ├── 04_結合テスト/
│   └── 05_ADR/            設計判断の記録
├── src/
│   ├── backend/           Java (Spring Boot)
│   └── frontend/          React + TypeScript
├── docker-compose.yml      ローカル開発用の PostgreSQL
└── .editorconfig
```

## ドキュメント

| 文書 | 内容 |
| --- | --- |
| [要件定義書](doc/01_要件定義/要件定義書.md) | システム化の範囲、就業規則、業務ルール（BR-01〜12）、非機能要件 |
| [開発環境構築手順](doc/00_開発環境構築手順.md) | Eclipse を使った環境構築と Git の運用 |
| [詳細設計](doc/02_詳細設計/) | アーキテクチャ、ドメインモデル、DB 設計、API 設計 |
| [ADR](doc/05_ADR/) | なぜその設計を選んだかの記録 |

## 開発環境の構築

前提：JDK 21、Docker、Node.js 22、Git。
Eclipse を使う場合の画面操作レベルの手順は [開発環境構築手順](doc/00_開発環境構築手順.md) を参照。

```bash
# 1. データベースを起動
docker compose up -d

# 2. バックエンド
cd src/backend
./gradlew bootRun

# 3. フロントエンド
cd src/frontend
npm install
npm run dev
```

## マイルストーン

| ID | 内容 | 状態 |
| --- | --- | --- |
| M0 | 開発環境の整備 | 進行中 |
| M1-a | マスタと固定時間制の日次計算 | — |
| M1-b | フレックスの月次清算、週 40 時間超の判定、36 協定の監視 | — |
| M1-c | 認証・認可、打刻訂正、提出 → 承認 → 締め | — |
| M2 | 年次有給休暇 | — |
| M3 | シフトと外部連携 | — |

詳細は[要件定義書 第 8 章](doc/01_要件定義/要件定義書.md#8-開発マイルストーン)を参照。
