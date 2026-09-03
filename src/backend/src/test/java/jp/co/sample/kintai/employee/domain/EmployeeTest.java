package jp.co.sample.kintai.employee.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.shared.domain.BusinessRuleViolationException;
import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/** 社員の単体テスト（UT-EMP-01〜04）。 */
@DisplayName("社員")
class EmployeeTest {

    private static final LocalDate HIRED = LocalDate.of(2026, 4, 1);

    private static Employee employee(Optional<LocalDate> retiredOn, Set<Role> roles) {
        return new Employee(new EmployeeId(UUID.randomUUID()), new EmployeeNumber("E0001"),
                "山田 太郎", new Email("e0001@example.com"), HIRED, retiredOn, roles);
    }

    private static Employee active() {
        return employee(Optional.empty(), Set.of(Role.EMPLOYEE));
    }

    @Nested
    @DisplayName("在籍期間")
    class ActivePeriod {

        /**
         * <strong>退職日は最終在籍日である。</strong>
         * 閉区間の感覚のまま半開区間に混ぜると退職日当日の 1 日が消え、
         * 最終日の勤怠の承認者が導出できなくなる（CLAUDE.md 落とし穴 10）。
         */
        @Test
        @DisplayName("UT-EMP-01 入社日当日は在籍、前日は非在籍。退職日当日は在籍、翌日は非在籍")
        void boundaries() {
            var retired = employee(Optional.of(LocalDate.of(2026, 9, 20)),
                    Set.of(Role.EMPLOYEE));

            assertThat(retired.isActiveOn(LocalDate.of(2026, 3, 31))).as("入社前日").isFalse();
            assertThat(retired.isActiveOn(HIRED)).as("入社日当日").isTrue();
            assertThat(retired.isActiveOn(LocalDate.of(2026, 9, 20))).as("退職日当日").isTrue();
            assertThat(retired.isActiveOn(LocalDate.of(2026, 9, 21))).as("退職日翌日").isFalse();
        }

        @Test
        @DisplayName("UT-EMP-04 退職日 3/31 の在籍期間は [入社日, 4/1)")
        void closedToHalfOpen() {
            var retired = employee(Optional.of(LocalDate.of(2027, 3, 31)),
                    Set.of(Role.EMPLOYEE));

            assertThat(retired.activePeriod())
                    .isEqualTo(new DateRange(HIRED, LocalDate.of(2027, 4, 1)));
        }

        @Test
        @DisplayName("在籍中の社員の在籍期間は上限を持たない")
        void activeEmployeeHasNoUpperBound() {
            assertThat(active().activePeriod().isUnbounded()).isTrue();
            assertThat(active().isActiveOn(LocalDate.of(2099, 1, 1))).isTrue();
            assertThat(active().isRetired()).isFalse();
        }

        @Test
        @DisplayName("退職させると退職日を持つ")
        void retire() {
            var retired = active().retire(LocalDate.of(2026, 9, 20));

            assertThat(retired.isRetired()).isTrue();
            assertThat(retired.isActiveOn(LocalDate.of(2026, 9, 20))).isTrue();
            assertThat(active().isRetired()).as("元の社員は変わらない").isFalse();
        }
    }

    @Nested
    @DisplayName("不変条件")
    class Invariants {

        @Test
        @DisplayName("UT-EMP-02 退職日が入社日より前だと生成できない")
        void retiredBeforeHired() {
            assertThatThrownBy(() ->
                    employee(Optional.of(LocalDate.of(2026, 3, 31)), Set.of(Role.EMPLOYEE)))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("退職日が入社日より前になっています");
        }

        /** 入社日当日の退職（1 日だけ在籍）は正当。 */
        @Test
        @DisplayName("退職日が入社日と同じなら生成できる")
        void retiredOnTheHiringDay() {
            var oneDay = employee(Optional.of(HIRED), Set.of(Role.EMPLOYEE));

            assertThat(oneDay.activePeriod().days()).isEqualTo(1);
        }

        /** 自分の打刻ができない社員を生成できなくする（要件 4 章）。 */
        @Test
        @DisplayName("UT-EMP-03 EMPLOYEE ロールを欠くと生成できない")
        void withoutEmployeeRole() {
            assertThatThrownBy(() -> employee(Optional.empty(), Set.of(Role.HR)))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("全社員が EMPLOYEE ロールを持つ必要があります");
        }

        @Test
        @DisplayName("ロールの集合は外から書き換えられない")
        void rolesAreImmutable() {
            var employee = active();

            assertThatThrownBy(() -> employee.roles().add(Role.ADMIN))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("氏名が空だと生成できない")
        void blankName() {
            assertThatThrownBy(() -> new Employee(new EmployeeId(UUID.randomUUID()),
                    new EmployeeNumber("E0001"), "  ", new Email("e@example.com"),
                    HIRED, Optional.empty(), Set.of(Role.EMPLOYEE)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("氏名は必須です");
        }
    }
}
