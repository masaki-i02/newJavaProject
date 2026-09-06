package jp.co.sample.kintai.shared.probe;

import jp.co.sample.kintai.payroll.domain.ExclusionReason;

/**
 * AR-10 違反。{@code shared} が {@code payroll} を知っている状態。
 *
 * <p>コンテキストを増やしたら AR-10 の禁止先も増やす。
 * 増やさないと、そのコンテキストだけ検査対象から外れる。
 * <strong>表に足しただけでは検査は 1 行も増えない</strong>ので、
 * 違反クラスを 1 つ置き、禁止先から削ると自己検査が落ちることを変異で確かめる
 * （CLAUDE.md 落とし穴 84・86・87）。
 */
public class SharedReachesIntoPayroll {

    public ExclusionReason reason() {
        return ExclusionReason.NOT_CLOSED;
    }
}
