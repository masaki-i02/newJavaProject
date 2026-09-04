package jp.co.sample.kintai.leave.domain;

import java.time.LocalDate;

import jp.co.sample.kintai.shared.domain.DateRange;

/**
 * 年 5 日の取得義務の充足状況（BR-17・労基法 39 条 7 項）。
 *
 * <p><strong>可視化までを担う。使用者による時季指定は実装しない。</strong>
 * 未達でも取得・提出・承認・締めのいずれも止めない。
 *
 * <p><strong>行を持たない。</strong> 取得のたびに更新する列を持つと、取下げで戻し忘れる。
 *
 * @param grantId   対象の付与
 * @param grantedOn 付与日。<strong>期間の起点は社員ごとに違う</strong>
 * @param takenDays その期間中に取得した年休の日数
 */
public record AnnualObligation(PaidLeaveGrantId grantId, LocalDate grantedOn, int takenDays) {

    /** 取得しなければならない日数。 */
    public static final int REQUIRED_DAYS = 5;

    /** 義務が生じる付与日数の下限（39 条 7 項）。 */
    public static final int TARGET_GRANT_DAYS = 10;

    public AnnualObligation {
        if (grantId == null || grantedOn == null) {
            throw new IllegalArgumentException("取得義務の項目に null は許されません");
        }
        if (takenDays < 0) {
            throw new IllegalArgumentException("取得日数が負です: " + takenDays);
        }
    }

    /**
     * 義務の期間。付与日から 1 年。<strong>半開区間</strong>。
     *
     * <p>暦年でも年度でもない。社員ごとに違う。
     */
    public DateRange period() {
        return new DateRange(grantedOn, grantedOn.plusYears(1));
    }

    /** 利用者に示す期限日。<strong>閉区間の最終日</strong>なので 1 日ずれる。 */
    public LocalDate deadline() {
        return period().toExclusive().minusDays(1);
    }

    public int shortfallDays() {
        return Math.max(0, REQUIRED_DAYS - takenDays);
    }

    public boolean isFulfilled() {
        return shortfallDays() == 0;
    }
}
