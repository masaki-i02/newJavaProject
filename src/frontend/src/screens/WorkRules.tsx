import { useCallback, useEffect, useState } from 'react';

import { get, post } from '../api/client';
import type { Presentation } from '../api/problem';
import type {
  ScheduleWarning, WorkRuleRegistration, WorkRuleRevision, WorkRuleSeries,
  WorkingTimeSystemType,
} from '../api/types';
import { previousDayOf } from '../api/wallClock';
import { ProblemBanner } from './ProblemBanner';
import { presentationOf } from './Punch';

/**
 * SC-10 就業規則（画面設計書 4.5）。
 *
 * ★ 「編集」ボタンを置かない。置くのは「**改定**」である。
 *   改定は版を足す操作であって、既存の版を書き換える操作ではない。
 *   編集と見せると、過去分の再計算結果まで変わると誤解される。
 *
 * ★ 一覧が指すのは**系列**であって版ではない。
 *   版を指させると、改定した瞬間に全参照が切れる（落とし穴 13）。
 *
 * ★ 改定の応答の `warnings` を、成功の知らせと並べて必ず出す。
 *   所定総労働時間が法定の総枠を超える月の知らせで、握りつぶすと人事が気づけない。
 */
export function WorkRules() {
  const [series, setSeries] = useState<readonly WorkRuleSeries[]>([]);
  const [selected, setSelected] = useState<WorkRuleSeries | null>(null);
  const [problem, setProblem] = useState<Presentation | null>(null);
  const [listProblem, setListProblem] = useState<Presentation | null>(null);
  const [warnings, setWarnings] = useState<readonly ScheduleWarning[]>([]);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [mode, setMode] = useState<'none' | 'register' | 'revise'>('none');

  const reload = useCallback(async () => {
    try {
      setSeries(await get<readonly WorkRuleSeries[]>('/api/work-rules'));
      setListProblem(null);
    } catch (error) {
      setSeries([]);
      setListProblem(presentationOf(error));
    }
  }, []);

  useEffect(() => { void reload(); }, [reload]);

  async function open(seriesId: string) {
    setProblem(null);
    setMode('none');
    try {
      setSelected(await get<WorkRuleSeries>(`/api/work-rules/${seriesId}`));
    } catch (error) {
      setProblem(presentationOf(error));
    }
  }

  async function submitForm(form: WorkRuleForm) {
    setProblem(null);
    setWarnings([]);
    setNotice(null);
    setBusy(true);
    try {
      // ★ 登録と改定で経路が違う。改定は系列の版を必ず送る（同時編集の上書きを防ぐ）
      const registered = mode === 'register'
        ? await post<WorkRuleRegistration>('/api/work-rules', bodyOf(form))
        : await post<WorkRuleRegistration>(
          `/api/work-rules/${selected?.seriesId ?? ''}/revisions`,
          { ...bodyOf(form), version: selected?.version ?? 0 });
      setWarnings(registered.warnings ?? []);
      setNotice(mode === 'register' ? '就業規則を登録しました。' : '就業規則を改定しました。');
      setMode('none');
      await reload();
      // ★ 応答の版ではなく、読み直した系列を持つ。
      //   登録直後に改定できるようにするため（落とし穴 158）
      await open(registered.seriesId);
    } catch (error) {
      setProblem(presentationOf(error));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main>
      <ProblemBanner presentation={problem} />
      {notice !== null && <div className="warning" role="status">{notice}</div>}
      {warnings.map((warning) => (
        <div className="warning" role="alert" key={warning.month}>
          {warning.message}
        </div>
      ))}

      <section>
        <h2>就業規則</h2>
        <ProblemBanner presentation={listProblem} />
        {listProblem === null && series.length === 0
          ? <p className="muted">就業規則がまだ登録されていません。</p>
          : (
            <table>
              <thead>
                <tr><th>名称</th><th className="num">版</th><th>廃止日</th><th /></tr>
              </thead>
              <tbody>
                {series.map((row) => (
                  <tr key={row.seriesId}>
                    <td>{row.name}</td>
                    <td className="num">{row.version}</td>
                    <td>{row.abolishedOn ?? ''}</td>
                    <td>
                      <button className="action secondary"
                              onClick={() => void open(row.seriesId)}>開く</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        <p>
          <button className="action" disabled={busy}
                  onClick={() => { setSelected(null); setMode('register'); }}>
            新しい就業規則を登録する
          </button>
        </p>
      </section>

      {selected !== null && (
        <section>
          <h2>{selected.name}</h2>
          <p className="muted">系列 {selected.seriesId} ／ 版 {selected.version}</p>
          {/* ★ `revisions` は省略ではなく null が来る（一覧の行では返らない） */}
          <RevisionTable revisions={selected.revisions ?? []} />
          <p>
            <button className="action" disabled={busy}
                    onClick={() => setMode('revise')}>改定する</button>
          </p>
        </section>
      )}

      {mode !== 'none' && (
        <WorkRuleFormSection mode={mode} busy={busy}
                             onCancel={() => setMode('none')}
                             onSubmit={(form) => void submitForm(form)} />
      )}
    </main>
  );
}

/**
 * 版の履歴。
 *
 * ★ 期間は閉区間で見せる。半開区間の上限をそのまま出すと、
 *   「4/30 まで有効」を「5/1 まで有効」と読ませる（落とし穴 10・112）。
 */
function RevisionTable({ revisions }: { revisions: readonly WorkRuleRevision[] }) {
  if (revisions.length === 0) {
    return <p className="muted">版がありません。</p>;
  }
  return (
    <table>
      <thead>
        <tr>
          <th>適用期間</th><th>制度</th><th>所定</th>
          <th>深夜帯</th><th className="num">法定日/週</th><th>割増率</th>
        </tr>
      </thead>
      <tbody>
        {revisions.map((revision) => (
          <tr key={revision.workRuleId}>
            <td>
              {revision.validFrom} 〜 {revision.validToExclusive === undefined
                ? '（現行）'
                : previousDayOf(revision.validToExclusive)}
            </td>
            <td>{systemLabel(revision.workingTimeSystem)}</td>
            <td>{scheduleLabel(revision)}</td>
            <td>{revision.nightWindow.start.slice(0, 5)}–{revision.nightWindow.end.slice(0, 5)}</td>
            <td className="num">
              {hoursLabel(revision.statutoryDailyMinutes)}
              {' / '}
              {hoursLabel(revision.statutoryWeeklyMinutes)}
            </td>
            <td>
              時間外 {revision.premiumRates.overtimeBeyondStatutory}
              ／深夜 {revision.premiumRates.night}
              ／法定休日 {revision.premiumRates.legalHoliday}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** 制度の表示。網羅性検査つきの `switch` で書く（`default` を置かない）。 */
export function systemLabel(system: WorkingTimeSystemType): string {
  switch (system) {
    case 'FIXED': return '固定時間制';
    case 'FLEX': return 'フレックス';
  }
}

/**
 * 所定の表示。
 *
 * ★ 判別子で絞る（画面設計書 5.3）。`fixedTime?` と `flextime?` を並べた形にすると、
 *   **両方が省かれた値**を型が認めてしまい、`undefined.scheduledStart` を書く経路が
 *   コンパイルを通る。判別可能ユニオンなら、制度が増えた瞬間に
 *   ここが `default` の無い `switch` として落ちる。
 */
function scheduleLabel(revision: WorkRuleRevision): string {
  switch (revision.workingTimeSystem) {
    case 'FIXED': {
      const fixed = revision.fixedTime;
      return `${fixed.scheduledStart.slice(0, 5)}–${fixed.scheduledEnd.slice(0, 5)}`
        + `（休憩 ${fixed.scheduledBreakMinutes} 分`
        + `・実働 ${hoursLabel(fixed.scheduledWorkingMinutes)}）`;
    }
    case 'FLEX': {
      const flex = revision.flextime;
      return `フレキシブル ${flex.flexibleStart.slice(0, 5)}–${flex.flexibleEnd.slice(0, 5)}`
        + `／コア ${flex.coreStart.slice(0, 5)}–${flex.coreEnd.slice(0, 5)}`
        + `／標準 ${hoursLabel(flex.standardDailyMinutes)}`;
    }
  }
}

/** 分を `8:00` の形にする。 */
export function hoursLabel(minutes: number): string {
  return `${Math.floor(minutes / 60)}:${String(minutes % 60).padStart(2, '0')}`;
}

/** 入力の中身。制度は排他なので、選択で切り替える。 */
export interface WorkRuleForm {
  readonly name: string;
  readonly validFrom: string;
  readonly system: WorkingTimeSystemType;
  readonly scheduledStart: string;
  readonly scheduledEnd: string;
  readonly scheduledBreakMinutes: string;
  readonly flexibleStart: string;
  readonly flexibleEnd: string;
  readonly coreStart: string;
  readonly coreEnd: string;
  readonly standardDailyMinutes: string;
}

const EMPTY_FORM: WorkRuleForm = {
  name: '',
  validFrom: '',
  system: 'FIXED',
  scheduledStart: '09:00',
  scheduledEnd: '18:00',
  scheduledBreakMinutes: '60',
  flexibleStart: '07:00',
  flexibleEnd: '22:00',
  coreStart: '11:00',
  coreEnd: '15:00',
  standardDailyMinutes: '480',
};

/**
 * 送る本文を組み立てる。
 *
 * ★ `fixedTime` と `flextime` を同時に入れない。
 *   両方入れるとサーバが 422 で拒む（DB の CHECK 制約が禁じた状態を
 *   API が再現しないようにするため）。画面の側でも作れなくしておく。
 *
 * ★ 法定値と割増率と深夜帯は送らない。
 *   送らなければサーバが法定どおりの既定を入れる。画面から動かせるようにすると、
 *   法定労働時間を 12 時間にして割増の対象を消せてしまう（落とし穴 15）。
 */
function bodyOf(form: WorkRuleForm): Record<string, unknown> {
  const system = form.system === 'FIXED'
    ? {
      fixedTime: {
        scheduledStart: form.scheduledStart,
        scheduledEnd: form.scheduledEnd,
        scheduledBreakMinutes: Number(form.scheduledBreakMinutes),
      },
    }
    : {
      flextime: {
        flexibleStart: form.flexibleStart,
        flexibleEnd: form.flexibleEnd,
        coreStart: form.coreStart,
        coreEnd: form.coreEnd,
        standardDailyMinutes: Number(form.standardDailyMinutes),
      },
    };
  return { name: form.name, validFrom: form.validFrom, system };
}

function WorkRuleFormSection({ mode, busy, onCancel, onSubmit }: {
  mode: 'register' | 'revise';
  busy: boolean;
  onCancel: () => void;
  onSubmit: (form: WorkRuleForm) => void;
}) {
  const [form, setForm] = useState<WorkRuleForm>(EMPTY_FORM);
  const change = <K extends keyof WorkRuleForm>(key: K, value: WorkRuleForm[K]) =>
    setForm((current) => ({ ...current, [key]: value }));

  return (
    <section>
      <h2>{mode === 'register' ? '就業規則の登録' : '就業規則の改定'}</h2>
      {mode === 'revise' && (
        <p className="muted">
          改定は版を足す操作です。<strong>過去の版は書き換わりません。</strong>
        </p>
      )}
      {mode === 'register' && (
        <p>
          <label htmlFor="wr-name">名称</label><br />
          <input id="wr-name" value={form.name} size={40}
                 onChange={(e) => change('name', e.target.value)} />
        </p>
      )}
      <p>
        {/* ★ 適用開始日は月初日、または当該社員の入社日。
              月初日だけに限ると、月中入社の初月が締められない */}
        <label htmlFor="wr-from">適用開始日</label><br />
        <input id="wr-from" type="date" value={form.validFrom}
               onChange={(e) => change('validFrom', e.target.value)} />
      </p>
      <p>
        <label htmlFor="wr-system">労働時間制度</label><br />
        <select id="wr-system" value={form.system}
                onChange={(e) => change('system',
                  e.target.value === 'FLEX' ? 'FLEX' : 'FIXED')}>
          <option value="FIXED">固定時間制</option>
          <option value="FLEX">フレックスタイム制</option>
        </select>
      </p>

      {form.system === 'FIXED' ? (
        <>
          <p>
            <label htmlFor="wr-start">始業</label>{' '}
            <input id="wr-start" type="time" value={form.scheduledStart}
                   onChange={(e) => change('scheduledStart', e.target.value)} />
            {' '}
            <label htmlFor="wr-end">終業</label>{' '}
            <input id="wr-end" type="time" value={form.scheduledEnd}
                   onChange={(e) => change('scheduledEnd', e.target.value)} />
          </p>
          <p>
            <label htmlFor="wr-break">休憩（分）</label>{' '}
            <input id="wr-break" type="number" min={0} value={form.scheduledBreakMinutes}
                   onChange={(e) => change('scheduledBreakMinutes', e.target.value)} />
          </p>
        </>
      ) : (
        <>
          <p>
            <label htmlFor="wr-flex-start">フレキシブル開始</label>{' '}
            <input id="wr-flex-start" type="time" value={form.flexibleStart}
                   onChange={(e) => change('flexibleStart', e.target.value)} />
            {' '}
            <label htmlFor="wr-flex-end">終了</label>{' '}
            <input id="wr-flex-end" type="time" value={form.flexibleEnd}
                   onChange={(e) => change('flexibleEnd', e.target.value)} />
          </p>
          <p>
            <label htmlFor="wr-core-start">コア開始</label>{' '}
            <input id="wr-core-start" type="time" value={form.coreStart}
                   onChange={(e) => change('coreStart', e.target.value)} />
            {' '}
            <label htmlFor="wr-core-end">終了</label>{' '}
            <input id="wr-core-end" type="time" value={form.coreEnd}
                   onChange={(e) => change('coreEnd', e.target.value)} />
          </p>
          <p>
            <label htmlFor="wr-standard">1 日の標準労働時間（分）</label>{' '}
            <input id="wr-standard" type="number" min={1} value={form.standardDailyMinutes}
                   onChange={(e) => change('standardDailyMinutes', e.target.value)} />
          </p>
        </>
      )}

      <div>
        <button className="action" disabled={busy || !isComplete(form, mode)}
                onClick={() => onSubmit(form)}>
          {mode === 'register' ? '登録する' : '改定する'}
        </button>
        <button className="action secondary" disabled={busy}
                onClick={onCancel}>やめる</button>
      </div>
    </section>
  );
}

/**
 * 送れる状態か。
 *
 * ★ ここで検証するのは「空でないこと」だけである。
 *   法定の範囲（所定 8 時間以下・コアタイムがフレキシブルタイムの内側）は
 *   サーバが業務エラーとして返す。画面で判定すると規則が 2 か所に生まれる。
 */
function isComplete(form: WorkRuleForm, mode: 'register' | 'revise'): boolean {
  if (form.validFrom === '') return false;
  if (mode === 'register' && form.name.trim() === '') return false;
  return form.system === 'FIXED'
    ? form.scheduledStart !== '' && form.scheduledEnd !== ''
      && form.scheduledBreakMinutes !== ''
    : form.flexibleStart !== '' && form.flexibleEnd !== ''
      && form.coreStart !== '' && form.coreEnd !== ''
      && form.standardDailyMinutes !== '';
}
