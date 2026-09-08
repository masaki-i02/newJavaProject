import { useEffect, useState } from 'react';

import { get } from '../api/client';
import type { Presentation } from '../api/problem';
import type { DepartmentNode, DepartmentTree } from '../api/types';
import { ProblemBanner } from './ProblemBanner';
import { presentationOf } from './Punch';

/**
 * SC-14 組織図（画面設計書 4.7）。
 *
 * ★ **閲覧範囲の絞り込みは API が行う。** 画面は返ってきた木をそのまま描く。
 *   一般社員には自分の所属と承認者だけ、承認者には配下部署、
 *   人事と管理者には全社が返る。
 *   画面側で絞ると、`GET /api/departments` を直接叩けば全社が見えてしまう。
 *
 * ★ 廃止済みの部署は既定で返らない。見たいときだけ明示して取る。
 *   常に見せると、現存する組織と廃止済みが混ざって読めなくなる。
 */
export function Organization() {
  const [nodes, setNodes] = useState<readonly DepartmentNode[]>([]);
  const [includeAbolished, setIncludeAbolished] = useState(false);
  const [problem, setProblem] = useState<Presentation | null>(null);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    void (async () => {
      try {
        setNodes((await get<DepartmentTree>(
          `/api/departments?includeAbolished=${String(includeAbolished)}`)).departments);
        setProblem(null);
      } catch (error) {
        setNodes([]);
        setProblem(presentationOf(error));
      } finally {
        setLoaded(true);
      }
    })();
  }, [includeAbolished]);

  return (
    <main>
      <ProblemBanner presentation={problem} />
      <section>
        <h2>組織図</h2>
        <p className="muted">
          見える範囲はロールで決まります。絞り込みはサーバが行います。
        </p>
        <p>
          <label>
            <input type="checkbox" checked={includeAbolished}
                   onChange={(e) => setIncludeAbolished(e.target.checked)} />
            {' '}廃止済みの部署も表示する
          </label>
        </p>
        {/* ★ 読み込みに失敗したまま「読み込み中…」を出し続けない（落とし穴 144） */}
        {!loaded ? <p className="muted">読み込み中…</p>
          : problem !== null ? null
            : nodes.length === 0
              ? <p className="muted">見える部署がありません。</p>
              : <Tree nodes={nodes} />}
      </section>
    </main>
  );
}

function Tree({ nodes }: { nodes: readonly DepartmentNode[] }) {
  return (
    <ul>
      {nodes.map((node) => (
        <li key={node.id}>
          <strong>{node.name}</strong>（{node.code}）
          {/* ★ 部署長がいないことを空欄で濁さない。
                承認者の遡りが根へ到達できなくなる状態を人事が見つけられる */}
          {node.manager === null
            ? <span className="muted">　部署長なし</span>
            : <span className="muted">　部署長：{node.manager.name}（{node.manager.since} 就任）</span>}
          {node.abolishedOn !== null
            && <span className="muted">　{node.abolishedOn} 廃止</span>}
          {node.children.length > 0 && <Tree nodes={node.children} />}
        </li>
      ))}
    </ul>
  );
}
