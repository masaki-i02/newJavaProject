package jp.co.sample.kintai.payroll.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.employee.domain.EmployeeNumber;
import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.workrule.domain.WorkingTimeSystemType;

/**
 * 給与へ渡す 1 行の不変条件（UT-PAY-01〜06・BR-18）。
 *
 * <p><strong>これらは本番の経路では働かない。</strong>
 * 行はすべて 1 つの月次清算から導くので、集約の側で既に成り立っている。
 * それでも置くのは、行を<strong>手で組み立てた</strong>ときに気づけるようにするためである
 * （CLAUDE.md 落とし穴 58）。「別の出どころから受け取るから検査になる」とは書かない。
 */
@DisplayName("給与へ渡す 1 行（BR-18）")
class PayrollRowTest {

    private static final EmployeeNumber NUMBER = new EmployeeNumber("E0001");
    private static final YearMonth MAY = YearMonth.of(2026, 5);
    private static final DateRange PERIOD =
            new DateRange(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 1));

    @Test
    @DisplayName("UT-PAY-01 所定内 + 所定超が実労働と一致しないと生成できない")
    void insideAndBeyondMustSumToWorkingTime() {
        assertThatThrownBy(() -> row()
                .workingTime(Duration.ofHours(160))
                .scheduledInsideTime(Duration.ofHours(150))
                .beyondScheduledTime(Duration.ofHours(5))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("所定内 + 所定超が実労働と一致しません");
    }

    @Test
    @DisplayName("UT-PAY-02 割増の対象時間が実労働を超えると生成できない")
    void premiumCannotExceedWorkingTime() {
        assertThatThrownBy(() -> row()
                .workingTime(Duration.ofHours(160))
                .scheduledInsideTime(Duration.ofHours(160))
                .beyondScheduledTime(Duration.ZERO)
                .overtimeUpTo60Time(Duration.ofHours(100))
                .legalHolidayTime(Duration.ofHours(61))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("割増の対象時間が実労働を超えています");
    }

    @Test
    @DisplayName("UT-PAY-03 所定内労働が所定総を超えると生成できない")
    void scheduledInsideCannotExceedScheduledTotal() {
        assertThatThrownBy(() -> row()
                .workingTime(Duration.ofHours(170))
                .scheduledInsideTime(Duration.ofHours(170))
                .beyondScheduledTime(Duration.ZERO)
                .scheduledTotalTime(Duration.ofHours(160))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("所定内労働が所定総を超えています");
    }

    @Test
    @DisplayName("UT-PAY-04 不足時間が所定総を超えると生成できない")
    void shortageCannotExceedScheduledTotal() {
        assertThatThrownBy(() -> row()
                .scheduledTotalTime(Duration.ofHours(160))
                .shortageTime(Duration.ofHours(161))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不足時間が所定総を超えています");
    }

    @Test
    @DisplayName("UT-PAY-05 日数を負にできない")
    void daysCannotBeNegative() {
        assertThatThrownBy(() -> row().absentDays(-1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("日数を負にはできません");
    }

    /**
     * <strong>出勤日数が所定労働日数を超える月は適法に存在する。</strong>
     *
     * <p>法定休日・所定休日に出勤した日は出勤日数に数えるが、所定労働日数には入らない。
     * ここに {@code 出勤日数 ≤ 所定労働日数} を置くと、休日出勤のある月を保存できなくなる
     * （CLAUDE.md 落とし穴 23・51）。だから欠勤日数を引き算で導かせない。
     */
    @Test
    @DisplayName("UT-PAY-06 出勤日数が所定労働日数を超える月も生成できる")
    void attendedDaysMayExceedScheduledDays() {
        assertThatCode(() -> row()
                .scheduledDays(20)
                .attendedDays(22)   // 所定労働日 20 日 + 法定休日に 2 日出勤
                .absentDays(0)
                .build())
                .doesNotThrowAnyException();
    }

    private static Builder row() {
        return new Builder();
    }

    /** 1 項目だけを差し替えて行を組み立てる（落とし穴 12）。 */
    private static final class Builder {

        private int scheduledDays = 20;
        private int attendedDays = 20;
        private int paidLeaveDays;
        private int absentDays;
        private Duration workingTime = Duration.ofHours(160);
        private Duration scheduledInsideTime = Duration.ofHours(160);
        private Duration beyondScheduledTime = Duration.ZERO;
        private Duration overtimeUpTo60Time = Duration.ZERO;
        private Duration overtimeOver60Time = Duration.ZERO;
        private Duration legalHolidayTime = Duration.ZERO;
        private Duration nightTime = Duration.ZERO;
        private Duration scheduledTotalTime = Duration.ofHours(160);
        private Duration shortageTime = Duration.ZERO;

        Builder scheduledDays(int value) {
            this.scheduledDays = value;
            return this;
        }

        Builder attendedDays(int value) {
            this.attendedDays = value;
            return this;
        }

        Builder absentDays(int value) {
            this.absentDays = value;
            return this;
        }

        Builder workingTime(Duration value) {
            this.workingTime = value;
            return this;
        }

        Builder scheduledInsideTime(Duration value) {
            this.scheduledInsideTime = value;
            return this;
        }

        Builder beyondScheduledTime(Duration value) {
            this.beyondScheduledTime = value;
            return this;
        }

        Builder overtimeUpTo60Time(Duration value) {
            this.overtimeUpTo60Time = value;
            return this;
        }

        Builder legalHolidayTime(Duration value) {
            this.legalHolidayTime = value;
            return this;
        }

        Builder scheduledTotalTime(Duration value) {
            this.scheduledTotalTime = value;
            return this;
        }

        Builder shortageTime(Duration value) {
            this.shortageTime = value;
            return this;
        }

        PayrollRow build() {
            return new PayrollRow(NUMBER, MAY, PERIOD, WorkingTimeSystemType.FIXED,
                    scheduledDays, attendedDays, paidLeaveDays, absentDays,
                    workingTime, scheduledInsideTime, beyondScheduledTime,
                    overtimeUpTo60Time, overtimeOver60Time, legalHolidayTime, nightTime,
                    scheduledTotalTime, shortageTime);
        }
    }
}
