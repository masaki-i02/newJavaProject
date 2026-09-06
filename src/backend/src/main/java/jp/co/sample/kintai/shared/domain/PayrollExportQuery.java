package jp.co.sample.kintai.shared.domain;

/**
 * 給与連携の出力が済んでいるかを問い合わせるポート。
 *
 * <p>出力の記録を持つのは {@code payroll} だが、それを知りたいのは {@code workrule} である。
 * 素直に辺を足すと <strong>{@code workrule → payroll → workrule} の循環</strong>が生じるので、
 * ポートを {@code shared} に置き、実装を {@code payroll/infrastructure} に置く（ADR 0004）。
 *
 * <p><strong>なぜ要るか。</strong>
 * 割増賃金の基礎額の分母（労基則 19 条 1 項 4 号）は<strong>年度全体</strong>の
 * 所定労働日数から決まる。締め済みの月のカレンダーは変えられないが、
 * <strong>同じ年度のまだ来ていない月は変えられる。</strong>
 * 4 月分の給与を払ったあとに 12 月の休日を 1 日増やすと、
 * 年度の分母が変わり、<strong>既に払った割増賃金の単価が事後的に足りなくなる。</strong>
 * 労基法 37 条の割増賃金は下限なので、下回った月には差額の支払義務が残る。
 *
 * <p>誰も気づけないまま起こるのが問題の本体なので、変更の側で止める。
 */
public interface PayrollExportQuery {

    /**
     * その年度の分母を使った出力が 1 件でもあるか。
     *
     * @param fiscalYear 年度（4 月 〜 翌 3 月）
     */
    boolean hasExportUsing(int fiscalYear);
}
