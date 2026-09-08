package jp.co.sample.kintai.attendance.domain.monthly;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import jp.co.sample.kintai.attendance.domain.DailyAttendance;
import jp.co.sample.kintai.shared.domain.TimeRange;
import jp.co.sample.kintai.workrule.domain.DayType;
import jp.co.sample.kintai.workrule.domain.FlextimeSystem;

/**
 * コアタイム不在（BR-05）。
 *
 * <p>フレックスタイム制で「必ず労働する」と定めた帯に労働していない時間を数える。
 *
 * <p><strong>賃金の計算には影響させない。</strong>
 * 承認者への警告として示すだけである。コアタイム不在は就業規則違反であって、
 * 労働時間の計算とは別の問題だから
 * （[04 ドメインモデル設計書 2.5](../../../../../../../../../doc/02_詳細設計/04_勤怠_月次清算/ドメインモデル設計書.md)）。
 *
 * <p><strong>所定労働日だけを数える。</strong>
 * 休日にコアタイムの義務は無いので、休日出勤した日の「コアタイム帯に
 * 働いていない時間」を不在として数えると、休んだ人より休日に働いた人のほうが
 * 違反が多いという逆の結果になる。
 *
 * <p><strong>固定時間制には当てない。</strong> コアタイムという概念が無い。
 * 制度の分岐は呼ぶ側（{@link MonthlySettlementCalculator}）が
 * 網羅性検査つきの {@code switch} で行う（落とし穴 22）。
 */
public final class CoreTimeAbsenceRule {

    private CoreTimeAbsenceRule() {
    }

    /**
     * 1 日ぶんの不在時間。
     *
     * <p>コアタイム帯のうち、労働区間と重ならない時間である。
     * 労働区間は休憩で分かれているので、<strong>重なりを足してから引く。</strong>
     * 区間ごとに「コアタイム − その区間」を足すと、区間の数だけ二重に数える。
     */
    public static Duration on(DailyAttendance day, FlextimeSystem system) {
        if (day == null || system == null) {
            throw new IllegalArgumentException("コアタイム不在の引数に null は許されません");
        }
        if (day.dayType() != DayType.WORKDAY) {
            return Duration.ZERO;
        }
        TimeRange core = system.coreTime().on(day.workDate());
        Duration worked = day.slices().stream()
                .map(slice -> slice.range().intersect(core))
                .flatMap(Optional::stream)
                .map(TimeRange::duration)
                .reduce(Duration.ZERO, Duration::plus);
        // ★ 負にしない。コアタイムを跨いで働いた区間が複数あっても、
        //   重なりの合計がコアタイムの長さを超えることはない（区間は重ならない）が、
        //   将来その不変条件が崩れたときに負の不在時間を返さない
        Duration absence = core.duration().minus(worked);
        return absence.isNegative() ? Duration.ZERO : absence;
    }

    /** 期間ぶんの合計。 */
    public static Duration total(List<DailyAttendance> days, FlextimeSystem system) {
        return days.stream()
                .map(day -> on(day, system))
                .reduce(Duration.ZERO, Duration::plus);
    }
}
