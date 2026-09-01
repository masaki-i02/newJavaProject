# src

このリポジトリのソースコードを置く。

| ディレクトリ | 内容 | 技術 |
| --- | --- | --- |
| `backend/` | バックエンド | Java 21 / Spring Boot 4.1 / Gradle (Kotlin DSL) / PostgreSQL |
| `frontend/` | フロントエンド | React / TypeScript / Vite |

## backend の生成条件

[Spring Initializr](https://start.spring.io/) で以下の設定を用いて生成する。

| 項目 | 値 |
| --- | --- |
| Project | Gradle - Kotlin |
| Language | Java |
| Spring Boot | 4.1.1（SNAPSHOT / M 版は選ばない） |
| Group | `jp.co.sample` |
| Artifact | `kintai` |
| Package name | `jp.co.sample.kintai` |
| Packaging | Jar |
| Configuration | YAML |
| Java | 21 |

**Dependencies**

```
Spring Web / Spring Data JPA / Spring Security / Validation
Flyway Migration / PostgreSQL Driver / Spring Boot Actuator
```

Lombok は使用しない。Java 21 の `record` があれば getter・equals・hashCode は不要であり、
`@Data` はドメインオブジェクトを可変にする方向に働くため。
