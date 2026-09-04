package jp.co.sample.kintai.leave.domain;

/**
 * 付与するかどうか（BR-14）。
 *
 * <p><strong>「不付与なのに日数がある」を型で作れなくする。</strong>
 * {@code boolean granted} と {@code Integer days} を並べて持つと、
 * 両者が食い違った状態を生成できてしまう。
 */
public sealed interface GrantDecision permits GrantDecision.Granted, GrantDecision.Withheld {

    /** 付与した。 */
    record Granted(int days) implements GrantDecision {

        public Granted {
            // 法定の表にある日数しか存在しない（BR-14）。上限も置く（落とし穴 15）
            if (days < LeaveEntitlement.MIN_DAYS || days > LeaveEntitlement.MAX_DAYS) {
                throw new IllegalArgumentException("付与日数が法定の範囲外です: " + days);
            }
        }
    }

    /**
     * 出勤率が 8 割に満たなかったので付与しなかった（BR-14）。
     *
     * <p><strong>不付与の年も行として残す。</strong> 残さないと
     * 「まだ付与処理をしていない」と「法どおり不付与にした」を区別できない。
     */
    record Withheld() implements GrantDecision {
    }
}
