import { useCallback, useEffect, useState } from 'react';

import { get, patch, post } from '../api/client';
import type { Presentation } from '../api/problem';
import type {
  DepartmentNode, DepartmentTree, EmployeeList, EmployeeRow, Role,
} from '../api/types';
import { ProblemBanner } from './ProblemBanner';
import { presentationOf } from './Punch';

/**
 * SC-12 社員一覧 / SC-13 社員の登録・編集。
 *
 * ★ 一覧は版を返さない。更新するときは詳細を開いて版を得る。
 *   一覧の版で更新すると、一覧を開いたまま誰かが更新したときに気づけない。
 *
 * ★ 名簿は**未来日入社の社員も返す**（落とし穴 75）。
 *   「その日に在籍していたか」で絞ると、登録直後の社員が一覧に現れず、
 *   管理者が登録の成否を確かめられない。
 *   だから `department` が `null` の行は異常ではない。
 */
export function Employees() {
  const [rows, setRows] = useState<readonly EmployeeRow[]>([]);
  const [departments, setDepartments] = useState<readonly DepartmentNode[]>([]);
  const [selected, setSelected] = useState<EmployeeRow | null>(null);
  const [includeRetired, setIncludeRetired] = useState(false);
  const [problem, setProblem] = useState<Presentation | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [registering, setRegistering] = useState(false);

  const reload = useCallback(async () => {
    try {
      setRows((await get<EmployeeList>(
        `/api/employees?includeRetired=${String(includeRetired)}`)).employees);
      setProblem(null);
    } catch (error) {
      setRows([]);
      setProblem(presentationOf(error));
    }
  }, [includeRetired]);

  useEffect(() => { void reload(); }, [reload]);

  // 登録には所属部署が要る。木を平らにして選択肢にする
  useEffect(() => {
    void (async () => {
      try {
        setDepartments((await get<DepartmentTree>('/api/departments')).departments);
      } catch {
        // 部署が引けなくても一覧は出す。登録のときに気づけばよい
        setDepartments([]);
      }
    })();
  }, []);

  async function open(row: EmployeeRow) {
    setProblem(null);
    setNotice(null);
    setRegistering(false);
    try {
      // ★ 版は詳細から取る。一覧の行には入っていない
      setSelected(await get<EmployeeRow>(`/api/employees/${row.id}`));
    } catch (error) {
      setProblem(presentationOf(error));
    }
  }

  async function save(name: string, email: string) {
    if (selected?.version === undefined) return;
    setProblem(null);
    setBusy(true);
    try {
      // ★ 応答を読み直して持つ。版は 1 つ進んでいる（落とし穴 158）
      setSelected(await patch<EmployeeRow>(`/api/employees/${selected.id}`,
        { name, email, version: selected.version }));
      setNotice('社員の情報を更新しました。');
      await reload();
    } catch (error) {
      setProblem(presentationOf(error));
    } finally {
      setBusy(false);
    }
  }

  async function register(body: RegistrationForm) {
    setProblem(null);
    setBusy(true);
    try {
      const created = await post<EmployeeRow>('/api/employees', {
        employeeNumber: body.employeeNumber,
        name: body.name,
        email: body.email,
        hiredOn: body.hiredOn,
        departmentId: body.departmentId,
        additionalRoles: body.additionalRoles,
      });
      setNotice(`${created.employeeNumber} を登録しました。`);
      setRegistering(false);
      await reload();
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

      <section>
        <h2>社員一覧</h2>
        <p>
          <label>
            <input type="checkbox" checked={includeRetired}
                   onChange={(e) => setIncludeRetired(e.target.checked)} />
            {' '}退職者も表示する
          </label>
        </p>
        {rows.length === 0
          ? <p className="muted">社員がいません。</p>
          : (
            <table>
              <thead>
                <tr>
                  <th>社員番号</th><th>氏名</th><th>所属</th>
                  <th>入社日</th><th>退職日</th><th>ロール</th><th />
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.id}>
                    <td>{row.employeeNumber}</td>
                    <td>{row.name}</td>
                    {/* ★ 所属が無いことを空欄で濁さない。未来日入社なら正常である */}
                    <td>{row.department?.name ?? '（所属なし）'}</td>
                    <td>{row.hiredOn}</td>
                    <td>{row.retiredOn ?? ''}</td>
                    <td>{row.roles.join(' / ')}</td>
                    <td>
                      <button className="action secondary"
                              onClick={() => void open(row)}>開く</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        <p>
          <button className="action" disabled={busy}
                  onClick={() => { setSelected(null); setRegistering(true); }}>
            社員を登録する
          </button>
        </p>
      </section>

      {selected !== null && (
        <EditSection employee={selected} busy={busy}
                     onSave={(name, email) => void save(name, email)} />
      )}

      {registering && (
        <RegistrationSection departments={flatten(departments)} busy={busy}
                             onCancel={() => setRegistering(false)}
                             onSubmit={(form) => void register(form)} />
      )}
    </main>
  );
}

/**
 * 部署ツリーを平らにする。
 *
 * ★ 木の形は SC-14 が見せる。ここで要るのは選択肢だけなので、
 *   深さをインデントで示して 1 段の一覧にする。
 */
export function flatten(nodes: readonly DepartmentNode[], depth = 0):
readonly { id: string; label: string }[] {
  return nodes.flatMap((node) => [
    { id: node.id, label: `${'　'.repeat(depth)}${node.name}` },
    ...flatten(node.children, depth + 1),
  ]);
}

function EditSection({ employee, busy, onSave }: {
  employee: EmployeeRow;
  busy: boolean;
  onSave: (name: string, email: string) => void;
}) {
  const [name, setName] = useState(employee.name);
  const [email, setEmail] = useState(employee.email);

  // 開き直したら入力も入れ替える
  useEffect(() => { setName(employee.name); setEmail(employee.email); },
    [employee.id, employee.name, employee.email]);

  return (
    <section>
      <h2>{employee.employeeNumber} の編集</h2>
      {/* ★ 社員番号・入社日・退職日・ロールはここでは変えられない。
            それぞれ別の操作（退職・ロールの変更）があり、証跡の残り方が違う */}
      <p className="muted">
        版 {employee.version ?? '—'} ／ 社員番号・入社日・ロールはここでは変更できません。
      </p>
      <p>
        <label htmlFor="emp-name">氏名</label><br />
        <input id="emp-name" value={name} size={30}
               onChange={(e) => setName(e.target.value)} />
      </p>
      <p>
        <label htmlFor="emp-email">メール</label><br />
        <input id="emp-email" value={email} size={40}
               onChange={(e) => setEmail(e.target.value)} />
      </p>
      <button className="action"
              disabled={busy || name.trim() === '' || email.trim() === ''}
              onClick={() => onSave(name, email)}>
        保存する
      </button>
    </section>
  );
}

interface RegistrationForm {
  readonly employeeNumber: string;
  readonly name: string;
  readonly email: string;
  readonly hiredOn: string;
  readonly departmentId: string;
  readonly additionalRoles: readonly Role[];
}

function RegistrationSection({ departments, busy, onCancel, onSubmit }: {
  departments: readonly { id: string; label: string }[];
  busy: boolean;
  onCancel: () => void;
  onSubmit: (form: RegistrationForm) => void;
}) {
  const [form, setForm] = useState<RegistrationForm>({
    employeeNumber: '', name: '', email: '', hiredOn: '',
    departmentId: '', additionalRoles: [],
  });
  const change = <K extends keyof RegistrationForm>(key: K,
                                                    value: RegistrationForm[K]) =>
    setForm((current) => ({ ...current, [key]: value }));

  return (
    <section>
      <h2>社員の登録</h2>
      <p>
        <label htmlFor="new-number">社員番号</label>{' '}
        <input id="new-number" value={form.employeeNumber}
               onChange={(e) => change('employeeNumber', e.target.value)} />
      </p>
      <p>
        <label htmlFor="new-name">氏名</label>{' '}
        <input id="new-name" value={form.name}
               onChange={(e) => change('name', e.target.value)} />
      </p>
      <p>
        <label htmlFor="new-email">メール</label>{' '}
        <input id="new-email" value={form.email} size={30}
               onChange={(e) => change('email', e.target.value)} />
      </p>
      <p>
        <label htmlFor="new-hired">入社日</label>{' '}
        <input id="new-hired" type="date" value={form.hiredOn}
               onChange={(e) => change('hiredOn', e.target.value)} />
      </p>
      <p>
        <label htmlFor="new-dept">所属部署</label>{' '}
        <select id="new-dept" value={form.departmentId}
                onChange={(e) => change('departmentId', e.target.value)}>
          <option value="">選択してください</option>
          {departments.map((d) => (
            <option key={d.id} value={d.id}>{d.label}</option>
          ))}
        </select>
      </p>
      <p>
        {/* ★ APPROVER は選ばせない。認証の時点で部署長の事実から導出される。
              ロールとして持たせると「部署長だがロールが無く 403」が起きる */}
        <label htmlFor="new-roles">追加のロール</label>{' '}
        <select id="new-roles" value={form.additionalRoles[0] ?? ''}
                onChange={(e) => change('additionalRoles',
                  e.target.value === '' ? [] : [asAssignableRole(e.target.value)])}>
          <option value="">なし（一般社員）</option>
          <option value="HR">HR（人事）</option>
          <option value="ADMIN">ADMIN（システム管理者）</option>
        </select>
      </p>
      <div>
        <button className="action" disabled={busy || !isComplete(form)}
                onClick={() => onSubmit(form)}>登録する</button>
        <button className="action secondary" disabled={busy}
                onClick={onCancel}>やめる</button>
      </div>
    </section>
  );
}

/**
 * 付与できるロール。
 *
 * ★ `as Role` で押し込まない。`EMPLOYEE` は全員が持つので指定不要、
 *   `APPROVER` は導出されるので指定できない。サーバも同じ判断で
 *   `not-assignable-role` を返す。
 */
export function asAssignableRole(value: string): Role {
  switch (value) {
    case 'HR': return 'HR';
    case 'ADMIN': return 'ADMIN';
    default: throw new Error(`付与できるロールではありません: ${value}`);
  }
}

function isComplete(form: RegistrationForm): boolean {
  return form.employeeNumber.trim() !== '' && form.name.trim() !== ''
    && form.email.trim() !== '' && form.hiredOn !== '' && form.departmentId !== '';
}
