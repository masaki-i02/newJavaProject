package jp.co.sample.kintai.attendance.presentation;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import jp.co.sample.kintai.attendance.domain.monthly.MonthlySettlement;
import jp.co.sample.kintai.attendance.domain.monthly.WeeklyOvertimeCharge;
import jp.co.sample.kintai.workrule.domain.WorkingTimeSystemType;

/**
 * 月次清算の応答（API 設計書 2）。
 *
 * <p><strong>制度によって意味を持つ項目が違う。</strong>
 * フレックスに週の内訳は存在せず、固定時間制で総枠と比べることはない。
 * 意味の無い項目は {@code null} にして応答から落とす。
 * 0 を返すと「0 時間だった」と読めてしまう。
 *
 * <p><strong>社員番号・氏名・部署を返さない。</strong>
 * それらは {@code employee} が所有する概念であり、
 * ここへ混ぜると、持っていない情報の提供者になってしまう（設計規約チェックリスト 3）。
 *
 * @param version 楽観ロックのための版。再計算の要求に必要なので返す
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MonthlySettlementResponse(
        String month,
        PeriodResponse period,
        long version,
        WorkingTimeSystemType workingTimeSystem,
        int workingMinutes,
        int legalHolidayMinutes,
        int targetWorkingMinutes,
        int scheduledTotalMinutes,
        int statutoryTotalLimitMinutes,
        Integer dailyOvertimeMinutes,
        Integer weeklyOvertimeMinutes,
        Integer carriedOverOvertimeMinutes,
        int overtimeMinutes,
        int overtimeOver60Minutes,
        int shortageMinutes,
        int nightMinutes,
        List<WeeklyBreakdownResponse> weeklyBreakdown,
        AgreementResponse agreement) {

    public static MonthlySettlementResponse from(MonthlySettlement settlement, long version) {
        boolean fixed = settlement.workingTimeSystem() == WorkingTimeSystemType.FIXED;
        var usage = settlement.agreementUsage();
        return new MonthlySettlementResponse(
                settlement.period().month().toString(),
                new PeriodResponse(settlement.period().period().from(),
                        settlement.period().period().toExclusive()),
                version,
                settlement.workingTimeSystem(),
                minutes(settlement.workingTime()),
                minutes(settlement.legalHolidayTime()),
                minutes(settlement.targetWorkingTime()),
                minutes(settlement.scheduledTotalTime()),
                minutes(settlement.statutoryTotalLimit()),
                fixed ? minutes(settlement.dailyOvertimeTime()) : null,
                fixed ? minutes(settlement.weeklyOvertimeTime()) : null,
                fixed ? minutes(settlement.carriedOverOvertimeTime()) : null,
                minutes(settlement.overtimeTime()),
                minutes(settlement.overtimeOver60Time()),
                minutes(settlement.shortageTime()),
                minutes(settlement.nightTime()),
                fixed ? settlement.weeklyBreakdown().stream()
                        .map(WeeklyBreakdownResponse::from).toList() : null,
                new AgreementResponse(
                        minutes(usage.subjectTime()),
                        minutes(usage.combinedTime()),
                        minutes(usage.monthlyLimit()),
                        usage.exceedsMonthly(),
                        minutes(usage.annualUsedBefore()),
                        minutes(usage.annualLimit()),
                        usage.exceedsAnnual(),
                        usage.exceedsCombinedSingleMonth()));
    }

    /**
     * 清算期間。
     *
     * <p><strong>暦月とは限らない。</strong>
     * 月中入社・月中退職の月は在籍期間との交差になるので、
     * 画面が「5/1 〜 5/31」と出せない月がある。
     */
    public record PeriodResponse(LocalDate from, LocalDate toExclusive) {
    }

    /**
     * 週ごとの内訳。
     *
     * @param weekOvertimeMinutes その週の 40 時間超の合計
     * @param chargedMinutes      うち<strong>この月に計上される分</strong>。
     *                            月をまたぐ週は 2 つの月に分かれる
     */
    public record WeeklyBreakdownResponse(LocalDate weekStart, LocalDate weekEnd,
                                          int statutoryInsideMinutes,
                                          int weekOvertimeMinutes, int chargedMinutes) {

        static WeeklyBreakdownResponse from(WeeklyOvertimeCharge week) {
            return new WeeklyBreakdownResponse(week.weekStart(),
                    week.weekEndExclusive().minusDays(1),
                    minutes(week.statutoryInsideTime()),
                    minutes(week.weekOvertimeTime()), minutes(week.chargedTime()));
        }
    }

    /**
     * 36 協定の消化状況。
     *
     * @param subjectMinutes  限度時間の対象。<strong>時間外労働だけ</strong>（36 条 3 項）
     * @param combinedMinutes 時間外 + 休日。単月 100 時間未満の対象（36 条 6 項 2 号）
     */
    public record AgreementResponse(int subjectMinutes, int combinedMinutes,
                                    int monthlyLimitMinutes, boolean exceedsMonthly,
                                    int annualUsedBeforeMinutes, int annualLimitMinutes,
                                    boolean exceedsAnnual,
                                    boolean exceedsCombinedSingleMonth) {
    }

    private static int minutes(Duration duration) {
        return Math.toIntExact(duration.toMinutes());
    }
}
