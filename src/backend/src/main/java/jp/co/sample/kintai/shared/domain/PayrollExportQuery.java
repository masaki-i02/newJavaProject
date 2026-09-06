package jp.co.sample.kintai.shared.domain;

import java.util.List;

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
 *
 * <p><strong>「出力したか」ではなく「いくつを分母に使ったか」を返す。</strong>
 * 真偽だけを返すと、1 回叩いただけで<strong>その年度のカレンダーを二度と直せなくなる。</strong>
 * 年度初めに仮登録して祝日を後から確定させる運用が封じられ、
 * 後半の月の暦日区分が誤ったまま固定される。
 * 止めるべきなのは<strong>分母が動く変更だけ</strong>なので、動いたかどうかを比べられる値を返す。
 */
public interface PayrollExportQuery {

    /**
     * <strong>行が出た</strong>出力がある年度と、その出力が使った年間の所定労働時間（分）。
     *
     * <p><strong>1 行も出なかった記録は数えない。</strong>
     * 全員が未締めで除外された記録は誰にも賃金を払っていないので、
     * その分母が事後的に変わっても損害が生じない。
     * 数えると、動作確認で 1 回叩いただけでその年度が凍結され、
     * 年度初めに仮登録して祝日を後から確定させる運用が封じられる。
     *
     * <p><strong>年度を 1 つずつ問い合わせる形にしない。</strong>
     * 就業規則の適用は期限を持たないので、変更が触れる年度の<strong>上限が無い</strong>。
     * 呼ぶ側が上限を決めると、その先の年度が素通りする。
     *
     * @param fiscalYear この年度以降を返す（4 月 〜 翌 3 月）
     * @return 年度の昇順。行が出た出力が無ければ空
     */
    List<UsedDivisor> usedDivisorsFrom(int fiscalYear);

    /**
     * 出力に使った分母。
     *
     * @param fiscalYear              年度
     * @param annualScheduledMinutes  その出力が使った年間の所定労働時間（分）
     */
    record UsedDivisor(int fiscalYear, long annualScheduledMinutes) {
    }
}
