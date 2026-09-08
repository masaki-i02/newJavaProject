package jp.co.sample.kintai.shared.infrastructure;

import java.time.LocalDate;
import java.util.Optional;

import jp.co.sample.kintai.shared.domain.DateRange;

/**
 * ドメインの<strong>番兵</strong>と SQL の {@code NULL} を相互に写す。
 *
 * <p>ドメインは無期限を {@link DateRange#UNBOUNDED_END}（{@code LocalDate.MAX}）で表す。
 * 端点に {@code null} を許すと、比較のたびに null 検査が要り、
 * 書き忘れた 1 か所で在籍判定が落ちるためである。
 *
 * <p>一方 <strong>番兵はそのままでは永続化できない。</strong>
 * PostgreSQL の {@code date} が表せる上限は 5874897 年で、{@code LocalDate.MAX} はそれを超える。
 * DDL 側は {@code valid_to date}（NULL 可）で無期限を表すので、
 * <strong>その写像はこの層の責務である</strong>（CLAUDE.md 落とし穴 35）。
 *
 * <p>この変換をアダプタごとに書くと、1 か所抜けただけで
 * 「無期限のはずの所属が 999999999 年で終わる」データが入る。ここ 1 か所に閉じる。
 *
 * <p><strong>{@code shared.infrastructure} に置く。</strong>
 * 有効期間を持つ表は {@code employee} だけではない（就業規則の版もそうである）。
 * かつては {@code employee.infrastructure} にあり、{@code workrule} 側が
 * 3 メソッドを 1 文字違わず複製していた。「1 か所に閉じる」と宣言しながら
 * 2 か所にあると、番兵の表現を変えたときに片方だけが古くなる（落とし穴 87）。
 */
public final class Periods {

    private Periods() {
    }

    /** 半開区間 → SQL の上限。無期限なら {@code null}。 */
    public static LocalDate toColumn(DateRange period) {
        return period.isUnbounded() ? null : period.toExclusive();
    }

    /** SQL の上限 → 半開区間。{@code null} は無期限。 */
    public static DateRange toRange(LocalDate from, LocalDate toExclusive) {
        return toExclusive == null
                ? DateRange.startingAt(from)
                : new DateRange(from, toExclusive);
    }

    /** 「最終日」の列（退職日・廃止日）→ ドメインの {@code Optional}。 */
    public static Optional<LocalDate> toOptional(LocalDate value) {
        return Optional.ofNullable(value);
    }
}
