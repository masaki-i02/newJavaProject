package jp.co.sample.kintai.attendance.domain.monthly;

import java.time.Duration;
import java.util.List;

import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.workrule.domain.SettlementPeriod;
import jp.co.sample.kintai.workrule.domain.WorkingTimeSystemType;

/**
 * 月次の清算結果（BR-05）。
 *
 * @param employeeId          対象の社員
 * @param period              清算期間。<strong>暦月 ∩ 在籍期間</strong>
 * @param workingTimeSystem   労働時間制度。永続化と表示のための判別値
 * @param workingTime         実労働の合計
 * @param legalHolidayTime    法定休日労働の合計
 * @param targetWorkingTime   対象労働時間 = 実労働 − 法定休日労働
 * @param scheduledTotalTime  所定総労働時間。下回ると不足時間
 * @param statutoryTotalLimit 法定労働時間の総枠。上回ると時間外労働
 * @param dailyOvertimeTime   日次で確定した法定外残業（固定時間制のみ）
 * @param weeklyOvertimeTime  週 40 時間超（固定時間制のみ）
 * @param carriedOverOvertimeTime 法定休日から翌暦日へ通算して生じた法定外残業（固定時間制のみ）
 * @param overtimeTime        時間外労働の合計
 * @param shortageTime        不足時間。欠勤控除の対象
 * @param nightTime           深夜労働の合計
 * @param weeklyBreakdown     週ごとの内訳
 * @param agreementUsage      36 協定の消化状況
 */
public record MonthlySettlement(
        EmployeeId employeeId,
        SettlementPeriod period,
        WorkingTimeSystemType workingTimeSystem,
        Duration workingTime,
        Duration legalHolidayTime,
        Duration targetWorkingTime,
        Duration scheduledTotalTime,
        Duration statutoryTotalLimit,
        Duration dailyOvertimeTime,
        Duration weeklyOvertimeTime,
        Duration carriedOverOvertimeTime,
        Duration overtimeTime,
        Duration shortageTime,
        Duration nightTime,
        List<WeeklyOvertime> weeklyBreakdown,
        AgreementUsage agreementUsage) {

    /**
     * 50% 割増に切り替わる月の時間外労働（労基法 37 条 1 項但書）。
     *
     * <p><strong>設定可能にしない。</strong> 法で固定された値であり、
     * 自由に動かせると割増率の下限だけ守っても 50% の対象そのものが消える
     * （CLAUDE.md 落とし穴 15）。
     */
    public static final Duration HIGH_RATE_THRESHOLD = Duration.ofHours(60);

    public MonthlySettlement {
        requireNonNull(employeeId, "社員");
        requireNonNull(period, "清算期間");
        requireNonNull(workingTimeSystem, "労働時間制度");
        requireNonNull(agreementUsage, "36 協定の消化状況");
        requireNonNull(weeklyBreakdown, "週ごとの内訳");
        for (Duration value : List.of(workingTime, legalHolidayTime, targetWorkingTime,
                scheduledTotalTime, statutoryTotalLimit, dailyOvertimeTime,
                weeklyOvertimeTime, carriedOverOvertimeTime, overtimeTime, shortageTime,
                nightTime)) {
            requireNonNull(value, "労働時間");
            if (value.isNegative()) {
                throw new IllegalArgumentException("労働時間を負にはできません: " + value);
            }
        }
        weeklyBreakdown = List.copyOf(weeklyBreakdown);

        // ★ 対象労働時間 = 実労働 − 法定休日労働。
        //   法定休日を含めると、休日に働いた分だけ時間外が水増しされ 35% と 25% の二重取りになる
        if (!targetWorkingTime.equals(workingTime.minus(legalHolidayTime))) {
            throw new IllegalArgumentException(
                    "対象労働時間の算出が一致しません: 対象 %s / 実労働 %s − 法定休日 %s"
                            .formatted(targetWorkingTime, workingTime, legalHolidayTime));
        }

        // ★ 制度ごとに時間外の内訳が決まる
        switch (workingTimeSystem) {
            case FIXED -> {
                Duration breakdown = dailyOvertimeTime.plus(weeklyOvertimeTime)
                        .plus(carriedOverOvertimeTime);
                if (!overtimeTime.equals(breakdown)) {
                    throw new IllegalArgumentException(
                            "時間外労働の内訳が一致しません: 合計 %s / 日次 %s + 週次 %s + 通算 %s"
                                    .formatted(overtimeTime, dailyOvertimeTime,
                                            weeklyOvertimeTime, carriedOverOvertimeTime));
                }
            }
            // フレックスは清算期間の総枠で判定する。日次・週次・通算を重ねると二重評価になる
            case FLEX -> {
                if (dailyOvertimeTime.isPositive() || weeklyOvertimeTime.isPositive()
                        || carriedOverOvertimeTime.isPositive()) {
                    throw new IllegalArgumentException(
                            "フレックスに日次・週次・通算の時間外を計上しています: "
                                    + "日次 %s / 週次 %s / 通算 %s".formatted(
                                            dailyOvertimeTime, weeklyOvertimeTime,
                                            carriedOverOvertimeTime));
                }
            }
        }

        // ★ フレックスに限り、時間外と不足が同時に正になるのは 所定総 > 法定総枠 の月だけ。
        //   フレックスでは時間外を「総枠に対する超過」、不足を「所定総に対する不足」として
        //   同じ実績（対象労働時間）から求めるので、2 つの基準が交差しない月では
        //   両方が正になりえない。両方が正なら計算の誤りである。
        //
        //   固定時間制にはこの検査を当てない。時間外は日次・週次で確定した実績であり、
        //   総枠との比較では求めていない。両者はまったく別の軸なので、
        //   「忙しい週に残業し、別の週に欠勤した月」は正当に両方が正になる。
        //   無条件の不変条件にすると、その月を保存できなくなる（CLAUDE.md 落とし穴 23）。
        if (workingTimeSystem == WorkingTimeSystemType.FLEX
                && overtimeTime.isPositive() && shortageTime.isPositive()
                && scheduledTotalTime.compareTo(statutoryTotalLimit) <= 0) {
            throw new IllegalArgumentException(
                    "所定総が法定総枠以下なのに、時間外と不足が同時に発生しています: "
                            + "時間外 %s / 不足 %s / 所定総 %s / 総枠 %s"
                                    .formatted(overtimeTime, shortageTime,
                                            scheduledTotalTime, statutoryTotalLimit));
        }

        // ★ 法定休日労働は実労働の一部である
        if (legalHolidayTime.compareTo(workingTime) > 0) {
            throw new IllegalArgumentException(
                    "法定休日労働が実労働時間を超えています: 法定休日 %s / 実労働 %s"
                            .formatted(legalHolidayTime, workingTime));
        }
    }

    private static void requireNonNull(Object value, String label) {
        if (value == null) {
            throw new IllegalArgumentException("%sに null は許されません".formatted(label));
        }
    }

    /**
     * 月 60 時間を超えた時間外労働。<strong>50% 割増の対象</strong>（労基法 37 条 1 項但書）。
     *
     * <p>25% と 50% を足すのではなく、60 時間を超えた部分の割増率が 50% になる。
     * 法定内残業（時間外労働ではない）と法定休日労働（36 条の対象だが時間外ではない）は
     * {@code overtimeTime} に入っていないので、ここにも入らない。
     *
     * <p><strong>制度で適用の有無は変わらない。</strong>
     * 37 条 1 項但書は時間外労働一般に対する規定であり、
     * フレックスの総枠超過も同じように 60 時間と比べる（要件定義書 0.5 の BR-05）。
     *
     * <p>{@code overtimeTime} から一意に決まるので<strong>項目として持たない。</strong>
     * 持つなら食い違いを禁じる不変条件が要る（CLAUDE.md 落とし穴 39）。
     */
    public Duration overtimeOver60Time() {
        Duration excess = overtimeTime.minus(HIGH_RATE_THRESHOLD);
        return excess.isNegative() ? Duration.ZERO : excess;
    }

    /** 50% 割増の対象が生じているか。 */
    public boolean hasHighRateOvertime() {
        return overtimeOver60Time().isPositive();
    }

    /** 所定どおり働いたか。不足があれば欠勤控除の対象になる。 */
    public boolean hasShortage() {
        return shortageTime.isPositive();
    }

    /** 36 協定の警告が立っているか。<strong>提出や承認は妨げない。</strong> */
    public boolean hasAgreementWarning() {
        return agreementUsage.hasWarning();
    }
}
