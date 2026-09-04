package jp.co.sample.kintai.leave.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.shared.domain.EmployeeId;

/** 残日数・時効・先入先出（BR-15）。 */
@DisplayName("年次有給休暇の残日数と先入先出")
class PaidLeaveBalanceTest {

    private static final EmployeeId EMPLOYEE = new EmployeeId(UUID.randomUUID());

    @Nested
    @DisplayName("時効")
    class Expiry {

        /** 付与日 + 2 年の当日には既に失効している（半開区間の上限）。 */
        @Test
        @DisplayName("UT-LV-19 付与日から 2 年で失効する")
        void expires() {
            PaidLeaveGrant grant = granted(0, LocalDate.of(2024, 10, 1), 10);
            assertThat(grant.isValidOn(LocalDate.of(2026, 10, 1))).isFalse();
        }

        @Test
        @DisplayName("UT-LV-20 失効日の前日は残数に入る")
        void dayBefore() {
            PaidLeaveGrant grant = granted(0, LocalDate.of(2024, 10, 1), 10);
            assertThat(grant.isValidOn(LocalDate.of(2026, 9, 30))).isTrue();
        }

        /**
         * <strong>うるう年の境界。</strong>
         * {@code plusYears(2)} は 2024-02-29 → 2026-02-28 に丸める。
         * SQL で {@code granted_on > asOf - interval '2 years'} と書くと、
         * 同じ日がまだ有効になって食い違う（落とし穴 91）。
         */
        @Test
        @DisplayName("UT-LV-52 2/29 に付与された年休は 2 年後の 2/28 に失効する")
        void leapYear() {
            PaidLeaveGrant grant = granted(0, LocalDate.of(2024, 2, 29), 10);
            assertThat(grant.validPeriod().toExclusive()).isEqualTo(LocalDate.of(2026, 2, 28));
            assertThat(grant.isValidOn(LocalDate.of(2026, 2, 27))).isTrue();
            assertThat(grant.isValidOn(LocalDate.of(2026, 2, 28))).isFalse();
        }
    }

    @Nested
    @DisplayName("先入先出")
    class FirstInFirstOut {

        @Test
        @DisplayName("UT-LV-21 古い付与から消化する")
        void oldestFirst() {
            PaidLeaveGrant older = granted(0, LocalDate.of(2025, 10, 1), 10);
            PaidLeaveGrant newer = granted(1, LocalDate.of(2026, 10, 1), 11);
            var balance = new PaidLeaveBalance(List.of(newer, older), List.of());

            assertThat(balance.allocationFor(LocalDate.of(2026, 11, 10)))
                    .contains(older.id());
        }

        /** 取得日に有効でない付与は選ばない。 */
        @Test
        @DisplayName("UT-LV-22 取得日より後に付与されたものは選ばない")
        void notYetGranted() {
            PaidLeaveGrant future = granted(1, LocalDate.of(2026, 10, 1), 11);
            var balance = new PaidLeaveBalance(List.of(future), List.of());

            assertThat(balance.allocationFor(LocalDate.of(2026, 9, 30))).isEmpty();
        }

        /**
         * <strong>6.5 年目の保有上限は 38 日である。</strong>
         * 6 年 6 か月の付与は 20 日だが、その前年（5 年 6 か月）は 18 日。
         */
        @Test
        @DisplayName("UT-LV-23 6.5 年目の保有上限は 38 日")
        void capAtSixAndHalf() {
            PaidLeaveGrant previous = granted(5, LocalDate.of(2025, 10, 1), 18);
            PaidLeaveGrant current = granted(6, LocalDate.of(2026, 10, 1), 20);
            var balance = new PaidLeaveBalance(List.of(previous, current), List.of());

            assertThat(balance.remainingDays(LocalDate.of(2026, 10, 1))).isEqualTo(38);
        }

        /** BR-15 が言う「最大 40 日」が成り立つのは 7.5 年目以降である。 */
        @Test
        @DisplayName("UT-LV-53 7.5 年目の保有上限は 40 日")
        void capAtSevenAndHalf() {
            PaidLeaveGrant previous = granted(6, LocalDate.of(2025, 10, 1), 20);
            PaidLeaveGrant current = granted(7, LocalDate.of(2026, 10, 1), 20);
            var balance = new PaidLeaveBalance(List.of(previous, current), List.of());

            assertThat(balance.remainingDays(LocalDate.of(2026, 10, 1))).isEqualTo(40);
        }
    }

    @Nested
    @DisplayName("残日数")
    class Remaining {

        /** 列を持たず、付与と配分から導く（ADR 0006）。 */
        @Test
        @DisplayName("UT-LV-24 残日数は付与と配分から導く")
        void derived() {
            PaidLeaveGrant grant = granted(0, LocalDate.of(2026, 4, 1), 10);
            var balance = new PaidLeaveBalance(List.of(grant), List.of(
                    new LeaveAllocation(grant.id(), LocalDate.of(2026, 5, 1)),
                    new LeaveAllocation(grant.id(), LocalDate.of(2026, 5, 2))));

            assertThat(balance.remainingDays(LocalDate.of(2026, 6, 1))).isEqualTo(8);
        }

        /**
         * <strong>承認済みだけを引くと、残 1 日に 2 件の申請が同時に通る。</strong>
         */
        @Test
        @DisplayName("UT-LV-25 未処理の申請を差し引いた申請可能日数")
        void pendingReduces() {
            PaidLeaveGrant grant = granted(0, LocalDate.of(2026, 4, 1), 10);
            var balance = new PaidLeaveBalance(List.of(grant), List.of());
            List<LocalDate> pending = List.of(LocalDate.of(2026, 6, 1));

            assertThat(balance.remainingDays(LocalDate.of(2026, 6, 1))).isEqualTo(10);
            assertThat(balance.availableDays(LocalDate.of(2026, 6, 1), pending)).isEqualTo(9);
        }

        /** 残 1 日に対して 2 件目は受理しない。 */
        @Test
        @DisplayName("残りが 1 日のとき、未処理が 1 件あれば申請できない")
        void lastDayTakenByPending() {
            PaidLeaveGrant grant = granted(0, LocalDate.of(2026, 4, 1), 10);
            List<LeaveAllocation> used = List.of(
                    new LeaveAllocation(grant.id(), LocalDate.of(2026, 5, 1)),
                    new LeaveAllocation(grant.id(), LocalDate.of(2026, 5, 2)),
                    new LeaveAllocation(grant.id(), LocalDate.of(2026, 5, 3)),
                    new LeaveAllocation(grant.id(), LocalDate.of(2026, 5, 4)),
                    new LeaveAllocation(grant.id(), LocalDate.of(2026, 5, 5)),
                    new LeaveAllocation(grant.id(), LocalDate.of(2026, 5, 6)),
                    new LeaveAllocation(grant.id(), LocalDate.of(2026, 5, 7)),
                    new LeaveAllocation(grant.id(), LocalDate.of(2026, 5, 8)),
                    new LeaveAllocation(grant.id(), LocalDate.of(2026, 5, 11)));
            var balance = new PaidLeaveBalance(List.of(grant), used);

            assertThat(balance.canAllocate(LocalDate.of(2026, 6, 1), List.of())).isTrue();
            assertThat(balance.canAllocate(LocalDate.of(2026, 6, 2),
                    List.of(LocalDate.of(2026, 6, 1)))).isFalse();
        }

        /**
         * <strong>件数の引き算では取りこぼす。</strong>
         * 合計では 10 日あるのに、その取得日に有効な付与が 1 つも無い。
         */
        @Test
        @DisplayName("UT-LV-26 合計は足りてもその日に有効な付与が無ければ申請できない")
        void notValidOnThatDate() {
            PaidLeaveGrant expired = granted(0, LocalDate.of(2024, 4, 1), 10);
            var balance = new PaidLeaveBalance(List.of(expired), List.of());

            // 2026-04-01 には既に失効している
            assertThat(balance.canAllocate(LocalDate.of(2026, 4, 1), List.of())).isFalse();
            assertThat(balance.remainingDays(LocalDate.of(2026, 3, 31))).isEqualTo(10);
        }

        /** 不付与の年は残日数に寄与しない。 */
        @Test
        @DisplayName("不付与の付与は残日数に入らない")
        void withheldNotCounted() {
            PaidLeaveGrant withheld = new PaidLeaveGrant(PaidLeaveGrantId.generate(), EMPLOYEE,
                    0, LocalDate.of(2026, 4, 1), AttendanceRate.of(245, 100),
                    new GrantDecision.Withheld(), LocalDateTime.of(2026, 4, 1, 0, 0), 1L);
            var balance = new PaidLeaveBalance(List.of(withheld), List.of());

            assertThat(balance.remainingDays(LocalDate.of(2026, 6, 1))).isZero();
            assertThat(balance.allocationFor(LocalDate.of(2026, 6, 1))).isEmpty();
        }
    }

    private static PaidLeaveGrant granted(int index, LocalDate grantedOn, int days) {
        return new PaidLeaveGrant(PaidLeaveGrantId.generate(), EMPLOYEE, index, grantedOn,
                AttendanceRate.of(245, 245), new GrantDecision.Granted(days),
                grantedOn.atStartOfDay(), 1L);
    }
}
