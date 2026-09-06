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

    /**
     * <strong>深夜は実労働の内側にある。</strong>
     * 重複属性なので排他区分の合計には入らないが、実労働を超えることはない（BR-06）。
     */
    @Test
    @DisplayName("UT-PAY-23 深夜労働が実労働を超えると生成できない")
    void nightCannotExceedWorkingTime() {
        assertThatThrownBy(() -> row()
                .workingTime(Duration.ofHours(160))
                .nightTime(Duration.ofHours(161))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("深夜労働が実労働を超えています");
    }

    /** 深夜が実労働と同じ長さになる月（全部が深夜帯）は適法に存在する。 */
    @Test
    @DisplayName("UT-PAY-24 深夜労働が実労働と同じ月は生成できる")
    void nightMayEqualWorkingTime() {
        assertThatCode(() -> row()
                .workingTime(Duration.ofHours(160))
                .nightTime(Duration.ofHours(160))
                .build())
                .doesNotThrowAnyException();
    }

    /** 年休と欠勤は所定労働日のうちである。合計が超えるなら数え方の誤り。 */
    @Test
    @DisplayName("UT-PAY-25 年休と欠勤の合計が所定労働日数を超えると生成できない")
    void leaveAndAbsenceCannotExceedScheduledDays() {
        assertThatThrownBy(() -> row()
                .scheduledDays(20)
                .paidLeaveDays(15)
                .absentDays(6)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("年休と欠勤の合計が所定労働日数を超えています");
    }

    /** 閾値の内側。20 日ちょうどは適法である（落とし穴 24）。 */
    @Test
    @DisplayName("UT-PAY-26 年休と欠勤の合計が所定労働日数と同じ月は生成できる")
    void leaveAndAbsenceMayFillScheduledDays() {
        assertThatCode(() -> row()
                .scheduledDays(20)
                .paidLeaveDays(15)
                .absentDays(5)
                .attendedDays(0)
                .workingTime(Duration.ZERO)
                .scheduledInsideTime(Duration.ZERO)
                .beyondScheduledTime(Duration.ZERO)
                .scheduledTotalTime(Duration.ofHours(40))
                .shortageTime(Duration.ofHours(40))
                .build())
                .doesNotThrowAnyException();
    }

    /**
     * <strong>清算期間の所定労働日数は暦月を超えない。</strong>
     * 日額の分母（暦月）のほうが必ず大きいか等しく、逆転していれば数え方の誤りである。
     */
    @Test
    @DisplayName("UT-PAY-27 清算期間の所定労働日数が暦月を超えると生成できない")
    void settlementDaysCannotExceedMonthlyDays() {
        assertThatThrownBy(() -> row()
                .monthlyScheduledDays(20)
                .scheduledDays(21)
                .attendedDays(21)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("清算期間の所定労働日数が暦月を超えています");
    }

    /**
     * <strong>月中入社の月は 2 つが食い違う。</strong>
     * 暦月 21 日・清算期間 11 日。日額の分母は前者である（労基法 24 条）。
     */
    @Test
    @DisplayName("UT-PAY-28 月中入社の月は暦月と清算期間で所定労働日数が違う")
    void monthlyAndSettlementDaysDifferForMidMonthHire() {
        assertThatCode(() -> row()
                .monthlyScheduledDays(21)
                .scheduledDays(11)
                .attendedDays(10)
                .absentDays(1)
                .workingTime(Duration.ofHours(80))
                .scheduledInsideTime(Duration.ofHours(80))
                .beyondScheduledTime(Duration.ZERO)
                .scheduledTotalTime(Duration.ofHours(88))
                .shortageTime(Duration.ofHours(8))
                .build())
                .doesNotThrowAnyException();
    }

    private static Builder row() {
        return new Builder();
    }

    /** 1 項目だけを差し替えて行を組み立てる（落とし穴 12）。 */
    private static final class Builder {

        private int monthlyScheduledDays = 20;
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

        Builder monthlyScheduledDays(int value) {
            this.monthlyScheduledDays = value;
            return this;
        }

        Builder scheduledDays(int value) {
            this.scheduledDays = value;
            return this;
        }

        /**
         * <strong>setter を欠かさない。</strong>
         * 既定 0 に固定される項目があると、
         * その項目が守っている不変条件へ<strong>破れた値を渡すことが物理的にできなくなる</strong>
         * （落とし穴 36）。
         */
        Builder paidLeaveDays(int value) {
            this.paidLeaveDays = value;
            return this;
        }

        Builder overtimeOver60Time(Duration value) {
            this.overtimeOver60Time = value;
            return this;
        }

        Builder nightTime(Duration value) {
            this.nightTime = value;
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
                    monthlyScheduledDays, scheduledDays, attendedDays, paidLeaveDays,
                    absentDays,
                    workingTime, scheduledInsideTime, beyondScheduledTime,
                    overtimeUpTo60Time, overtimeOver60Time, legalHolidayTime, nightTime,
                    scheduledTotalTime, shortageTime);
        }
    }
}
