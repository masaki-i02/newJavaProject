package jp.co.sample.kintai.leave.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.attendance.domain.DailyAttendance;
import jp.co.sample.kintai.employee.domain.Email;
import jp.co.sample.kintai.employee.domain.Employee;
import jp.co.sample.kintai.employee.domain.EmployeeNumber;
import jp.co.sample.kintai.leave.domain.AttendanceRate;
import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.Role;
import jp.co.sample.kintai.support.DailyAttendances;
import jp.co.sample.kintai.support.TestCalendar;

/**
 * 出勤率の数え方（BR-14・労基法 39 条 1 項）。
 *
 * <p><strong>閾値の判定は {@link AttendanceRate} が持つ</strong>ので、ここでは数えた
 * 分母と分子だけを見る。ただし UT-LV-12 は「出勤率が下がらない」という観点なので、
 * <strong>年休を数えなければ 8 割を割る</strong>ところに揃えて、
 * 判定の向きが変わることまで確かめる（落とし穴 24）。
 *
 * <p>日次勤怠は本番の計算を通して作る。手で組み立てると
 * 「どの日を働いたと見なすか」を代役が決めることになる（落とし穴 37）。
 */
@DisplayName("出勤率の集計")
class AttendanceRateCalculatorTest {

    private static final LocalDate FROM = LocalDate.of(2026, 4, 1);
    private static final EmployeeId ANYONE =
            new EmployeeId(java.util.UUID.randomUUID());

    private final TestCalendar calendar = TestCalendar.allWorkdays();
    private final DailyAttendances days = new DailyAttendances(calendar, ANYONE);

    /**
     * 年休を取得した日は出勤日に数える（39 条 10 項）。
     *
     * <p>数えないと、<strong>権利を行使した社員ほど翌年の付与を失う。</strong>
     */
    @Test
    @DisplayName("UT-LV-12 年休を取得した日は出勤日に数える")
    void paidLeaveCountsAsAttended() {
        Employee employee = active(FROM);
        DateRange period = new DateRange(FROM, FROM.plusDays(10));
        List<DailyAttendance> worked = workedDays(FROM, 7);

        AttendanceRate withLeave = calculator().of(employee, period, worked,
                Set.of(FROM.plusDays(7)));
        AttendanceRate withoutLeave = calculator().of(employee, period, worked, Set.of());

        assertThat(withLeave.totalWorkingDays()).isEqualTo(10);
        assertThat(withLeave.attendedDays()).isEqualTo(8);
        assertThat(withLeave.meetsThreshold()).isTrue();
        // ★ 年休を数えないと 7/10 で 8 割を割る。閾値をまたぐ値を選ぶ
        assertThat(withoutLeave.attendedDays()).isEqualTo(7);
        assertThat(withoutLeave.meetsThreshold()).isFalse();
    }

    /**
     * 欠勤の日は分母に入るが、分子には入らない（BR-14）。
     *
     * <p>出勤日を「日次勤怠の行がある日」で数えると、
     * <strong>労働時間 0 の行</strong>（出勤と退勤が同時刻・落とし穴 40）まで出勤に数える。
     * 欠勤しても出勤率が下がらなくなり、8 割の判定が意味を失う。
     */
    @Test
    @DisplayName("UT-LV-71 労働時間の無い日は出勤日に数えない")
    void absentDaysAreNotAttended() {
        Employee employee = active(FROM);
        DateRange period = new DateRange(FROM, FROM.plusDays(10));
        List<DailyAttendance> worked = new ArrayList<>(workedDays(FROM, 8));
        // 9 日目・10 日目は打刻が無い（欠勤）。行そのものは存在しうる
        worked.add(days.absent(FROM.plusDays(8)));
        worked.add(days.absent(FROM.plusDays(9)));

        AttendanceRate rate = calculator().of(employee, period, worked, Set.of());

        assertThat(rate.totalWorkingDays()).as("欠勤の日も分母には入る").isEqualTo(10);
        assertThat(rate.attendedDays()).as("労働時間が無いので分子には入らない").isEqualTo(8);
    }

    /** 所定休日・法定休日は分母に入らない（BR-07）。働く義務が無い日である。 */
    @Test
    @DisplayName("UT-LV-13 所定休日・法定休日は全労働日に数えない")
    void holidaysAreNotWorkingDays() {
        Employee employee = active(FROM);
        calendar.nonLegalHoliday(FROM.plusDays(5));
        calendar.legalHoliday(FROM.plusDays(6));
        DateRange period = new DateRange(FROM, FROM.plusDays(7));

        AttendanceRate rate = calculator().of(employee, period, workedDays(FROM, 5), Set.of());

        assertThat(rate.totalWorkingDays()).isEqualTo(5);
        assertThat(rate.attendedDays()).isEqualTo(5);
    }

    /**
     * 在籍していない日は分母に入らない（2.4 の表）。
     *
     * <p>付与の対象を「付与日に在籍している社員」に限っているので通常は起きないが、
     * <strong>付与のあとに退職した社員を再判定する</strong>と起きる。
     * 分母から外さないと、退職しただけで出勤率が下がって不付与に変わる。
     */
    @Test
    @DisplayName("UT-LV-14 在籍していない日は全労働日に数えない")
    void daysOutsideServiceAreNotCounted() {
        // 4/1 入社・4/5 退職（最終在籍日）。算定期間は 4/1〜4/10
        Employee retired = new Employee(new EmployeeId(UUID.randomUUID()),
                new EmployeeNumber("E0001"), "山田 太郎",
                new Email("e0001@example.com"), FROM,
                Optional.of(FROM.plusDays(4)), Set.of(Role.EMPLOYEE));
        DateRange period = new DateRange(FROM, FROM.plusDays(10));

        AttendanceRate rate = calculator().of(retired, period, workedDays(FROM, 5), Set.of());

        // 退職日（4/5）までの 5 日だけが分母。残り 5 日は在籍していない
        assertThat(rate.totalWorkingDays()).isEqualTo(5);
        assertThat(rate.attendedDays()).isEqualTo(5);
    }

    private AttendanceRateCalculator calculator() {
        return new AttendanceRateCalculator(calendar);
    }

    private List<DailyAttendance> workedDays(LocalDate from, int count) {
        List<DailyAttendance> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            result.add(days.fixedDay(from.plusDays(i), Duration.ofHours(8)));
        }
        return List.copyOf(result);
    }

    private static Employee active(LocalDate hiredOn) {
        return Employee.active(new EmployeeId(UUID.randomUUID()),
                new EmployeeNumber("E0001"), "山田 太郎",
                new Email("e0001@example.com"), hiredOn, Set.of(Role.EMPLOYEE));
    }
}
