/**
 * 設計書に埋め込む図を Mermaid のソースから PNG へ一括変換する。
 *
 *   cd doc/_tools && npm install && npm run render
 *
 * doc 配下の `diagrams/*.mmd` を探し、同じ階層の `images/` へ同名の PNG を出力する。
 *
 *   doc/02_詳細設計/03_勤怠/diagrams/ドメインモデル.mmd
 *     -> doc/02_詳細設計/03_勤怠/images/ドメインモデル.png
 *
 * 設計書を Markdown で書きつつ、開いた瞬間に図が見える状態を保つための仕組み。
 * Mermaid のまま埋め込むと閲覧環境によっては描画されないため、PNG を成果物とする。
 */
import { execFileSync } from 'node:child_process'
import { mkdirSync, readdirSync, statSync, writeFileSync, rmSync } from 'node:fs'
import { dirname, join, resolve, basename } from 'node:path'
import { fileURLToPath } from 'node:url'

const toolsDir = dirname(fileURLToPath(import.meta.url))
const docRoot = resolve(toolsDir, '..')

/** doc 配下から diagrams/*.mmd をすべて集める。 */
function collectSources(dir) {
  const found = []
  for (const name of readdirSync(dir)) {
    if (name === 'node_modules' || name.startsWith('.')) continue
    const path = join(dir, name)
    if (statSync(path).isDirectory()) {
      found.push(...collectSources(path))
    } else if (name.endsWith('.mmd') && basename(dir) === 'diagrams') {
      found.push(path)
    }
  }
  return found
}

/**
 * Puppeteer の設定を組み立てる。
 * CI やコンテナでは既存の Chromium を使いたいので、環境変数があればそれを優先する。
 */
function buildPuppeteerConfig() {
  const config = { args: ['--no-sandbox', '--disable-dev-shm-usage'] }
  if (process.env.PUPPETEER_EXECUTABLE_PATH) {
    config.executablePath = process.env.PUPPETEER_EXECUTABLE_PATH
  }
  const path = join(toolsDir, '.puppeteer-config.json')
  writeFileSync(path, JSON.stringify(config))
  return path
}

const sources = collectSources(docRoot)
if (sources.length === 0) {
  console.log('変換対象の .mmd が見つかりませんでした。')
  process.exit(0)
}

const puppeteerConfig = buildPuppeteerConfig()
const mmdc = join(toolsDir, 'node_modules', '.bin', process.platform === 'win32' ? 'mmdc.cmd' : 'mmdc')

let failed = 0
for (const source of sources) {
  const outputDir = join(dirname(dirname(source)), 'images')
  mkdirSync(outputDir, { recursive: true })
  const output = join(outputDir, basename(source, '.mmd') + '.png')
  try {
    execFileSync(mmdc, [
      '--input', source,
      '--output', output,
      '--configFile', join(toolsDir, 'mermaid-config.json'),
      '--puppeteerConfigFile', puppeteerConfig,
      '--backgroundColor', 'white',
      '--scale', '2',
    ], { stdio: 'pipe' })
    console.log('OK   ' + output.replace(docRoot + '/', ''))
  } catch (error) {
    failed++
    console.error('NG   ' + source.replace(docRoot + '/', ''))
    console.error(String(error.stderr ?? error.message).trim())
  }
}

rmSync(puppeteerConfig, { force: true })
console.log(`\n${sources.length - failed} / ${sources.length} 件を変換しました。`)
process.exit(failed === 0 ? 0 : 1)
