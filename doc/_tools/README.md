# 図の生成ツール

設計書の図は **Mermaid のソースを正**とし、成果物として **PNG** を生成してコミットする。

## なぜ PNG を成果物にするか

Markdown に Mermaid を直接埋め込むと、GitHub 上では描画されるが、
エディタやビューアによっては生のテキストが表示される。設計書は
**開いた瞬間に図が見える** 状態であるべきなので、PNG を生成して埋め込む。

一方で PNG だけを管理すると差分が追えず修正もできないため、ソースの `.mmd` も併せてコミットする。

## 使い方

```bash
cd doc/_tools
npm install       # 初回のみ
npm run render
```

`doc` 配下の `diagrams/*.mmd` を探し、同じ階層の `images/` に同名の PNG を出力する。

```
doc/02_詳細設計/03_勤怠/diagrams/ドメインモデル.mmd
  → doc/02_詳細設計/03_勤怠_打刻と日次集計/images/ドメインモデル.png
```

Markdown からは相対パスで参照する。

```markdown
![ドメインモデル](images/ドメインモデル.png)
```

## 図を修正するとき

**PNG を直接編集しない。** `.mmd` を直して `npm run render` を実行し、
両方をコミットする。

## 補足

Chromium を別途用意している環境では、環境変数で指定できる。

```bash
PUPPETEER_EXECUTABLE_PATH=/path/to/chrome npm run render
```
