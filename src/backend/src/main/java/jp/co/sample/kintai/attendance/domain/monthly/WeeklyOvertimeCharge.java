package jp.co.sample.kintai.attendance.domain.monthly;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * ある月の清算が引き受ける、1 週間ぶんの週 40 時間超（BR-04）。
 *
 * <p>{@link WeeklyOvertime} は<strong>週そのもの</strong>を表し、月をまたぐ週では
 * 超過が 2 つの月に分かれる。この型はそのうち<strong>1 つの月が引き受ける分</strong>を表す。
 *
 * <p>分けて持つのは、週の合計と月の計上額が別の値だからである。
 * 月次清算に週の合計だけを持たせると、月をまたぐ週で
 * <strong>時間外の合計と内訳が食い違う。</strong>
 * 逆に計上額だけを持たせると、画面に「その週は何時間だったのか」を出せない。
 *
 * @param weekStart           週の起算日（日曜）
 * @param weekEndExclusive    週の終了日。<strong>含まない</strong>
 * @param statutoryInsideTime その週の法定内労働時間の合計
 * @param weekOvertimeTime    その週の 40 時間超の合計
 * @param chargedTime         このうち当該月に計上される分
 */
public record WeeklyOvertimeCharge(LocalDate weekStart, LocalDate weekEndExclusive,
                                   Duration statutoryInsideTime, Duration weekOvertimeTime,
                                   Duration chargedTime) {

    public WeeklyOvertimeCharge {
        if (weekStart == null || weekEndExclusive == null || statutoryInsideTime == null
                || weekOvertimeTime == null || chargedTime == null) {
            throw new IllegalArgumentException("週次時間外の計上の項目に null は許されません");
        }
        if (!weekStart.isBefore(weekEndExclusive)) {
            throw new IllegalArgumentException(
                    "週の開始は終了より前である必要があります: [%s, %s)"
                            .formatted(weekStart, weekEndExclusive));
        }
        if (statutoryInsideTime.isNegative() || weekOvertimeTime.isNegative()
                || chargedTime.isNegative()) {
            throw new IllegalArgumentException("労働時間を負にはできません");
        }
        // ★ 計上額が週の超過を上回ることはありえない
        if (chargedTime.compareTo(weekOvertimeTime) > 0) {
            throw new IllegalArgumentException(
                    "計上額が週の時間外を超えています: 計上 %s / 週 %s"
                            .formatted(chargedTime, weekOvertimeTime));
        }
    }

    /** その月に計上される分を切り出す。 */
    public static WeeklyOvertimeCharge of(WeeklyOvertime week, YearMonth month) {
        return new WeeklyOvertimeCharge(week.weekStart(), week.weekEndExclusive(),
                week.statutoryInsideTime(), week.overtimeTime(),
                week.overtimeChargedTo(month));
    }
}
