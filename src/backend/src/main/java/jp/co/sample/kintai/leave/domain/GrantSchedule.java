package jp.co.sample.kintai.leave.domain;

import java.time.LocalDate;
import java.util.stream.IntStream;

import jp.co.sample.kintai.shared.domain.DateRange;

/**
 * 入社日から付与日と出勤率の算定期間を導く（BR-14）。
 *
 * <p><strong>付与基準日は入社日である。</strong> 一斉付与は採らない。
 * 初年度は法定どおりの前倒し付与が要り、結局この計算を内部に持つことになる。
 *
 * @param hiredOn 入社日
 */
public record GrantSchedule(LocalDate hiredOn) {

    /** 初回の付与までの月数。 */
    private static final int FIRST_GRANT_MONTHS = 6;

    public GrantSchedule {
        if (hiredOn == null) {
            throw new IllegalArgumentException("入社日に null は許されません");
        }
    }

    /**
     * {@code grantIndex} 回目の付与日（0 起点）。
     *
     * <p>0 回目は入社から 6 か月後、以後 1 年ごと。
     *
     * <p><strong>月末入社は {@code plusMonths} の丸めに従う。</strong>
     * 1/31 入社の 6 か月後は 7/31、8/31 入社の 6 か月後は翌年 2/28（うるう年は 2/29）。
     *
     * <p>民法 143 条 2 項によれば、応当日の無い月末入社では期間はその月の末日に満了し、
     * 法定の付与日は<strong>その翌日</strong>になる。{@code plusMonths} が返すのは満了日
     * そのものなので 1 日早い。<strong>労働者に有利な前倒しなので、これを採る。</strong>
     * 時効（付与日 + 2 年）と年 5 日の期間（付与日 + 1 年）も 1 日早く始まる。
     */
    public LocalDate grantDateOf(int grantIndex) {
        requireNonNegative(grantIndex);
        return hiredOn.plusMonths(FIRST_GRANT_MONTHS).plusYears(grantIndex);
    }

    /** {@code grantIndex} 回目の付与日数（BR-14）。 */
    public int daysOf(int grantIndex) {
        return LeaveEntitlement.of(grantIndex).days();
    }

    /**
     * 出勤率の算定期間。<strong>半開区間</strong>。
     *
     * <p>0 回目は入社日から最初の付与日まで（6 か月）、
     * 1 回目以降は前回の付与日から今回の付与日まで（1 年）。
     */
    public DateRange assessmentPeriodOf(int grantIndex) {
        requireNonNegative(grantIndex);
        LocalDate from = grantIndex == 0 ? hiredOn : grantDateOf(grantIndex - 1);
        return new DateRange(from, grantDateOf(grantIndex));
    }

    /**
     * 基準日までに到来した付与の連番を古い順に返す。
     *
     * <p><strong>古い順であることが必要である。</strong>
     * 1 回目の出勤率を判定するには 0 回目の年休の取得日が要る（年休は出勤扱い）。
     */
    public IntStream indexesDueOn(LocalDate asOf) {
        if (asOf == null) {
            throw new IllegalArgumentException("基準日に null は許されません");
        }
        if (asOf.isBefore(grantDateOf(0))) {
            return IntStream.empty();
        }
        // 二分探索するほどの回数ではない。上限は在籍年数ぶんで足りる
        int index = 0;
        while (!grantDateOf(index + 1).isAfter(asOf)) {
            index++;
        }
        return IntStream.rangeClosed(0, index);
    }

    private static void requireNonNegative(int grantIndex) {
        if (grantIndex < 0) {
            throw new IllegalArgumentException("付与の連番が負です: " + grantIndex);
        }
    }
}
