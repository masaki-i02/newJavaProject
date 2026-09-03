package jp.co.sample.kintai.employee.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.support.Organization;

/**
 * 組織構造の<strong>事実</strong>の導出（UT-EMP-05〜11）。
 *
 * <p>承認の可否はここでは判断しない。自己承認の禁止・退職者のスキップ・
 * 人事へのエスカレーションは {@code approval} の {@code ApproverPolicy} の責務である
 * （アーキテクチャ設計書 6.4）。
 */
@DisplayName("組織図（事実の導出）")
class OrganizationChartTest {

    private static final LocalDate APR_1 = LocalDate.of(2026, 4, 1);

    @Nested
    @DisplayName("所属部署")
    class DepartmentOf {

        @Test
        @DisplayName("UT-EMP-05 所属している日はその部署を返す")
        void assigned() {
            var org = Organization.empty();
            var sales = org.department("D001", "営業部");
            var taro = org.hire("E0001", APR_1);
            org.assign(taro, sales, APR_1);

            assertThat(org.chart().departmentOf(taro, APR_1))
                    .map(Department::name).contains("営業部");
        }

        @Test
        @DisplayName("UT-EMP-06 所属が無い日は空を返す（例外にしない）")
        void notAssigned() {
            var org = Organization.empty();
            var sales = org.department("D001", "営業部");
            var taro = org.hire("E0001", APR_1);
            org.assign(taro, sales, APR_1);

            assertThat(org.chart().departmentOf(taro, LocalDate.of(2026, 3, 31))).isEmpty();
        }

        /** 異動の境界。所属期間は半開区間なので、終了日当日は新しい部署に属する。 */
        @Test
        @DisplayName("UT-EMP-07 異動の前後で異なる部署を返す")
        void transfer() {
            var org = Organization.empty();
            var sales = org.department("D001", "営業部");
            var dev = org.department("D002", "開発部");
            var taro = org.hire("E0001", APR_1);
            var transferredOn = LocalDate.of(2026, 7, 1);
            org.assign(taro, sales, APR_1, transferredOn)
                    .assign(taro, dev, transferredOn);

            var chart = org.chart();
            assertThat(chart.departmentOf(taro, transferredOn.minusDays(1)))
                    .map(Department::name).contains("営業部");
            assertThat(chart.departmentOf(taro, transferredOn))
                    .map(Department::name).contains("開発部");
        }
    }

    @Nested
    @DisplayName("祖先の列挙")
    class Ancestors {

        @Test
        @DisplayName("UT-EMP-08 自分自身から根へ向かう順で返す")
        void selfAndAncestors() {
            var org = Organization.empty();
            var head = org.department("H001", "営業本部");
            var division = org.department("D001", "第一営業部", head);
            var section = org.department("S001", "第一営業課", division);

            assertThat(org.chart().selfAndAncestorsOf(section))
                    .extracting(Department::name)
                    .containsExactly("第一営業課", "第一営業部", "営業本部");
        }

        @Test
        @DisplayName("ルート部署は自分自身だけを返す")
        void rootOnly() {
            var org = Organization.empty();
            var head = org.department("H001", "営業本部");

            assertThat(org.chart().selfAndAncestorsOf(head)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("部署長")
    class Manager {

        @Test
        @DisplayName("UT-EMP-09 基準日時点の部署長を返す（交代を反映する）")
        void managerAtTheBasisDate() {
            var org = Organization.empty();
            var sales = org.department("D001", "営業部");
            var first = org.hire("E0002", APR_1);
            var second = org.hire("E0003", APR_1);
            var changedOn = LocalDate.of(2026, 7, 1);
            org.appoint(sales, first, APR_1, changedOn)
                    .appoint(sales, second, changedOn);

            var chart = org.chart();
            assertThat(chart.managerOf(sales, changedOn.minusDays(1)))
                    .map(Managership::employeeId).contains(first);
            assertThat(chart.managerOf(sales, changedOn))
                    .map(Managership::employeeId).contains(second);
        }

        @Test
        @DisplayName("長が未設定なら空を返す")
        void noManager() {
            var org = Organization.empty();
            var sales = org.department("D001", "営業部");

            assertThat(org.chart().managerOf(sales, APR_1)).isEmpty();
        }
    }

    @Nested
    @DisplayName("基準日の決定（BR-11 の 1）")
    class AssignmentStart {

        /**
         * <strong>この例外が無いと、月中入社の社員は初月の承認者が導出できず、
         * 勤怠を永久に締められない。</strong>
         */
        @Test
        @DisplayName("UT-EMP-10 月の途中で所属が始まっていればその開始日を返す")
        void startedMidMonth() {
            var org = Organization.empty();
            var sales = org.department("D001", "営業部");
            var taro = org.hire("E0001", LocalDate.of(2026, 4, 15));
            org.assign(taro, sales, LocalDate.of(2026, 4, 15));

            assertThat(org.chart().assignmentStartWithin(taro, YearMonth.of(2026, 4)))
                    .contains(LocalDate.of(2026, 4, 15));
        }

        /**
         * 月初日に始まっている場合は<strong>空を返す</strong>。
         * 「月の途中で始まった」ではないので、基準日は通常どおり月初日でよい。
         */
        @Test
        @DisplayName("UT-EMP-11 月初日に始まっていれば空を返す")
        void startedOnTheFirstDay() {
            var org = Organization.empty();
            var sales = org.department("D001", "営業部");
            var taro = org.hire("E0001", APR_1);
            org.assign(taro, sales, APR_1);

            assertThat(org.chart().assignmentStartWithin(taro, YearMonth.of(2026, 4))).isEmpty();
        }

        @Test
        @DisplayName("前月から続いている所属は対象にしない")
        void continuedFromThePreviousMonth() {
            var org = Organization.empty();
            var sales = org.department("D001", "営業部");
            var taro = org.hire("E0001", APR_1);
            org.assign(taro, sales, APR_1);

            assertThat(org.chart().assignmentStartWithin(taro, YearMonth.of(2026, 5))).isEmpty();
        }

        /** 月内に 2 回異動したら、最も早い開始日を採る。 */
        @Test
        @DisplayName("月内に複数の所属開始があれば最も早い日を返す")
        void earliestStart() {
            var org = Organization.empty();
            var sales = org.department("D001", "営業部");
            var dev = org.department("D002", "開発部");
            var taro = org.hire("E0001", LocalDate.of(2026, 4, 10));
            org.assign(taro, sales, LocalDate.of(2026, 4, 10), LocalDate.of(2026, 4, 20))
                    .assign(taro, dev, LocalDate.of(2026, 4, 20));

            assertThat(org.chart().assignmentStartWithin(taro, YearMonth.of(2026, 4)))
                    .contains(LocalDate.of(2026, 4, 10));
        }
    }

    @Nested
    @DisplayName("在籍判定")
    class Active {

        @Test
        @DisplayName("退職日当日は在籍、翌日は非在籍")
        void retirementBoundary() {
            var org = Organization.empty();
            var taro = org.hire("E0001", APR_1,
                    Optional.of(LocalDate.of(2026, 9, 20)), Role.EMPLOYEE);

            var chart = org.chart();
            assertThat(chart.isActiveOn(taro, LocalDate.of(2026, 9, 20))).isTrue();
            assertThat(chart.isActiveOn(taro, LocalDate.of(2026, 9, 21))).isFalse();
        }

        /** 存在しない社員は「在籍していない」。例外にしない。 */
        @Test
        @DisplayName("存在しない社員は非在籍として扱う")
        void unknownEmployee() {
            var org = Organization.empty();
            var ghost = Organization.empty().hire("E9999", APR_1);

            assertThat(org.chart().isActiveOn(ghost, APR_1)).isFalse();
        }
    }
}
