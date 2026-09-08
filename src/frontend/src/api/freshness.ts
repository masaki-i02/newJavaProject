import { useRef } from 'react';

/**
 * 古い応答で新しい状態を上書きしないための番号札。
 *
 * ★ 対象月を切り替えると、前の月の問い合わせが**まだ飛んでいる**。
 *   遅れて届いた前の月の応答が `setState` を呼ぶと、
 *   画面は新しい月を表示しているのに**中身は前の月のもの**になる。
 *
 * ★ これは「表示がずれる」だけでは済まない。
 *   `canSubmit` は月ごとに違うので、**終わった月を開いているのに
 *   提出ボタンが押せない**（前の月＝当月の `false` が残る）という形で出る。
 *   見た目は正しい月なので、利用者にも開発者にも原因が分からない。
 *   実際、実ブラウザの通しが 1 回だけ落ちて再現しなかった。
 *
 * ★ 画面ごとに書かない。同じ規則が 7 か所に散ると、
 *   直したときに片方だけが古くなる（落とし穴 67）。
 *
 * ★ 判定そのものは React に依存しない（{@link createFreshness}）。
 *   フックは「画面 1 つにつき番号札 1 つ」を保つだけである。
 *   依存しない形にしておくと、描画を起こさずに規則を検査できる。
 */
export type Freshness = () => () => boolean;

/**
 * 番号札を配る。
 *
 * 配った札は、**そのあとに札が配られていなければ**「新しい」と答える。
 */
export function createFreshness(): Freshness {
  let latest = 0;
  return () => {
    latest += 1;
    const ticket = latest;
    return () => ticket === latest;
  };
}

export function useFreshness(): Freshness {
  const held = useRef<Freshness | null>(null);
  held.current ??= createFreshness();
  return held.current;
}
