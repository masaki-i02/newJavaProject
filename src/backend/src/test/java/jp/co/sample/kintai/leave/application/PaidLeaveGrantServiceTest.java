package jp.co.sample.kintai.leave.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import jp.co.sample.kintai.attendance.domain.DailyAttendanceRepository;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrant;
import jp.co.sample.kintai.shared.application.AccessDeniedException;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.Requester;
import jp.co.sample.kintai.shared.domain.Role;
import jp.co.sample.kintai.support.DailyAttendances;
import jp.co.sample.kintai.support.Fixtures;
import jp.co.sample.kintai.support.IntegrationTestBase;
import jp.co.sample.kintai.workrule.domain.CompanyCalendar;
import jp.co.sample.kintai.workrule.domain.WorkRule;
import jp.co.sample.kintai.workrule.domain.WorkRuleRepository;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesId;

/** 年次有給休暇の付与（BR-14）。 */
@DisplayName("年次有給休暇の付与")
class PaidLeaveGrantServiceTest extends IntegrationTestBase {

    /**
     * 入社日。
     *
     * <p><strong>就業規則の有効期間（2026-01-01 以降）に収まる日を選ぶ。</strong>
     * 算定期間の日次勤怠は本番の計算を通して作るので、
     * その日に適用できる規則が無いと組み立てられない。
     */
    private static final LocalDate HIRED = LocalDate.of(2026, 4, 1);

    /** 入社から 6 か月後。0 回目の付与日。 */
    private static final LocalDate FIRST_GRANT = LocalDate.of(2026, 10, 1);

    @Autowired
    private PaidLeaveGrantService service;

    @Autowired
    private DailyAttendanceRepository dailyAttendances;

    @Autowired
    private WorkRuleRepository workRules;

    @Autowired
    private CompanyCalendar calendar;

    private Fixtures fixtures;
    private Requester hr;
    private Requester employee;

    @BeforeEach
    void setUp() {
        fixtures = new Fixtures(jdbc);
        EmployeeId hrId = hire("E0900", LocalDate.of(2020, 4, 1), null);
        fixtures.grantRole(hrId.value(), "HR");
        hr = new Requester(hrId, Set.of(Role.EMPLOYEE, Role.HR));
        employee = new Requester(hrId, Set.of(Role.EMPLOYEE));
    }

    @Nested
    @DisplayName("実行者")
    class Authorization {

        /**
         * 依頼そのものの不備は例外へ。全員を {@code skipped} にすると、
         * 人事は<strong>自分に権限が無いことに気づけない</strong>（落とし穴 60）。
         */
        @Test
        @DisplayName("IT-LV-112 人事でない社員は付与を実行できない")
        void notHumanResources() {
            assertThatThrownBy(() -> service.grantAsOf(employee, FIRST_GRANT))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Nested
    @DisplayName("対象")
    class Target {

        @Test
        @DisplayName("IT-LV-113 入社 6 か月後に 10 日が付与される")
        void firstGrant() {
            EmployeeId yamada = hireWorking("E0001", HIRED, null, 100);

            var result = service.grantAsOf(hr, FIRST_GRANT);

            assertThat(result.granted())
                    .anySatisfy(granted -> {
                        assertThat(granted.employeeId()).isEqualTo(yamada);
                        assertThat(granted.grantedOn()).isEqualTo(FIRST_GRANT);
                        assertThat(granted.days()).isEqualTo(10);
                    });
        }

        /** 付与日の前日には到来していない。閾値の反対側。 */
        @Test
        @DisplayName("IT-LV-114 付与日の前日には付与されない")
        void dayBefore() {
            EmployeeId yamada = hire("E0001", HIRED, null);

            var result = service.grantAsOf(hr, FIRST_GRANT.minusDays(1));

            assertThat(result.granted()).noneMatch(g -> g.employeeId().equals(yamada));
        }

        /**
         * <strong>退職者を除かないと毎年 20 日が積み上がる。</strong>
         * しかも退職者の算定期間は在籍期間で絞ると全労働日 0 になり、
         * 出勤率の判定を必ず通る（落とし穴 92）。
         */
        @Test
        @DisplayName("UT-LV-49 付与日の前日に退職した社員には付与されない")
        void retiredBeforeGrantDate() {
            EmployeeId taro = hire("E0002", HIRED, FIRST_GRANT.minusDays(1));

            var result = service.grantAsOf(hr, FIRST_GRANT);

            assertThat(result.granted()).noneMatch(g -> g.employeeId().equals(taro));
            assertThat(result.withheld()).noneMatch(w -> w.employeeId().equals(taro));
        }

        /** 付与日よりずっと前に退職した社員。退職者に毎年積み上がらない。 */
        @Test
        @DisplayName("UT-LV-48 付与日より前に退職した社員には付与されない")
        void retiredWellBeforeGrantDate() {
            EmployeeId jiro = hire("E0005", HIRED, HIRED.plusMonths(1));

            var result = service.grantAsOf(hr, FIRST_GRANT);

            assertThat(result.granted()).noneMatch(g -> g.employeeId().equals(jiro));
            assertThat(result.withheld()).noneMatch(w -> w.employeeId().equals(jiro));
        }

        /**
         * <strong>算定期間の全労働日が 0 になる退職者。</strong>
         *
         * <p>「全労働日 0 なら 8 割を満たしたものとする」という緩和（UT-LV-11）は、
         * 在籍期間で分母を絞ると<strong>退職者が必ず通る</strong>。
         * 付与日の在籍で絞っていないと、この社員に 2 回目の 11 日が付与される
         * （落とし穴 92）。緩和は「誰に当てるか」とセットでしか成り立たない。
         */
        @Test
        @DisplayName("UT-LV-63 前回の付与日より前に退職した社員には付与されない")
        void retiredBeforePreviousGrantDate() {
            // 1 回目（2027-10-01）の算定期間は [2026-10-01, 2027-10-01) で、
            // この社員は 2026-09-01 に退職しているので全労働日が 0 になる
            EmployeeId saburo = hire("E0006", HIRED, LocalDate.of(2026, 9, 1));

            var result = service.grantAsOf(hr, FIRST_GRANT.plusYears(1));

            assertThat(result.granted()).noneMatch(g -> g.employeeId().equals(saburo));
            assertThat(service.grantsOf(saburo)).isEmpty();
        }

        /** 付与日<strong>当日</strong>に在籍していれば付与される。閾値の反対側。 */
        @Test
        @DisplayName("IT-LV-115 付与日当日に退職する社員には付与される")
        void retiredOnGrantDate() {
            EmployeeId hanako = hireWorking("E0003", HIRED, FIRST_GRANT, 100);

            var result = service.grantAsOf(hr, FIRST_GRANT);

            assertThat(result.granted()).anyMatch(g -> g.employeeId().equals(hanako));
        }

        /** 未来日入社の社員は付与日が到来しないので自然に外れる。 */
        @Test
        @DisplayName("IT-LV-116 未来日入社の社員には付与されない")
        void futureHire() {
            EmployeeId future = hire("E0004", LocalDate.of(2030, 4, 1), null);

            var result = service.grantAsOf(hr, FIRST_GRANT);

            assertThat(result.granted()).noneMatch(g -> g.employeeId().equals(future));
        }
    }

    @Nested
    @DisplayName("冪等性")
    class Idempotence {

        /** 同じ日に 2 回実行しても二重に付与しない。 */
        @Test
        @DisplayName("IT-LV-117 同じ基準日で 2 回実行すると 2 回目は skipped になる")
        void twice() {
            EmployeeId yamada = hireWorking("E0001", HIRED, null, 100);
            service.grantAsOf(hr, FIRST_GRANT);

            var second = service.grantAsOf(hr, FIRST_GRANT);

            assertThat(second.granted()).noneMatch(g -> g.employeeId().equals(yamada));
            assertThat(second.skipped()).anySatisfy(skipped -> {
                assertThat(skipped.employeeId()).isEqualTo(yamada);
                assertThat(skipped.reason())
                        .isEqualTo(GrantResult.Skipped.ALREADY_GRANTED);
            });
            assertThat(service.grantsOf(yamada)).hasSize(1);
        }

        /**
         * <strong>当日ぶんだけを作らない。</strong>
         * バッチが動かなかった日があっても次の実行で追いつく（落とし穴 26）。
         */
        @Test
        @DisplayName("IT-LV-118 基準日を先に進めると、到来済みの未処理分がすべて作られる")
        void catchesUp() {
            EmployeeId yamada = hireWorking("E0001", HIRED, null, 100);

            service.grantAsOf(hr, FIRST_GRANT.plusYears(2));

            assertThat(service.grantsOf(yamada))
                    .extracting(PaidLeaveGrant::grantedOn)
                    .containsExactly(FIRST_GRANT, FIRST_GRANT.plusYears(1),
                            FIRST_GRANT.plusYears(2));
        }

        /** 不付与でも連番は進むので、2 回目の付与日数は 11 日になる（BR-14）。 */
        @Test
        @DisplayName("IT-LV-119 2 回目の付与は 11 日")
        void secondGrantDays() {
            EmployeeId yamada = hireWorking("E0001", HIRED, null, 100);

            var result = service.grantAsOf(hr, FIRST_GRANT.plusYears(1));

            // ★ 社員でも絞る。同じ基準日には他の社員（人事）の付与も並ぶ
            assertThat(result.granted())
                    .filteredOn(g -> g.employeeId().equals(yamada)
                            && g.grantedOn().equals(FIRST_GRANT.plusYears(1)))
                    .singleElement()
                    .extracting(GrantResult.Granted::days).isEqualTo(11);
        }
    }

    /** 全社員が EMPLOYEE ロールを持つ（要件 4 章）。持たない社員は生成できない。 */
    private EmployeeId hire(String number, LocalDate hiredOn, LocalDate retiredOn) {
        UUID id = fixtures.employee(number, hiredOn, retiredOn);
        fixtures.grantRole(id, "EMPLOYEE");
        return new EmployeeId(id);
    }

    /**
     * 出勤率 8 割を満たす社員を作る。
     *
     * <p><strong>暦日区分を登録しない日は所定労働日になる</strong>ので、
     * 183 日の算定期間をそのまま使うと全労働日 183・出勤 0 で必ず不付与になる。
     * 算定期間を所定休日で埋め、{@code workdays} 日だけを所定労働日にして出勤させる。
     */
    private EmployeeId hireWorking(String number, LocalDate hiredOn, LocalDate retiredOn,
                                   int workdays) {
        EmployeeId id = hire(number, hiredOn, retiredOn);
        // 2 回目の付与まで賄えるよう、入社から 3 年ぶんを所定休日で埋める
        for (LocalDate date = hiredOn; date.isBefore(hiredOn.plusYears(3));
                date = date.plusDays(1)) {
            jdbc.update("""
                    INSERT INTO company_calendars (calendar_date, day_type, name)
                    VALUES (?, 'NON_LEGAL_HOLIDAY', '所定休日')
                    ON CONFLICT (calendar_date) DO NOTHING
                    """, date);
        }
        for (int i = 0; i < workdays; i++) {
            LocalDate date = hiredOn.plusDays(i);
            jdbc.update("UPDATE company_calendars SET day_type = 'WORKDAY', name = '所定労働日'"
                    + " WHERE calendar_date = ?", date);
            // ★ 本番の計算を通して作る。行を手で書くと内訳（slices）が空になり、
            //   「内訳の合計 = 実労働時間」という不変条件に弾かれる（落とし穴 37・55）
            dailyAttendances.save(id,
                    new DailyAttendances(calendar).fixedDay(date, Duration.ofHours(8)),
                    workRule().id());
        }
        return id;
    }

    private WorkRule workRule() {
        if (workRule == null) {
            UUID seriesId = fixtures.workRuleSeries("標準勤務");
            fixtures.fixedWorkRule(seriesId, LocalDate.of(2020, 4, 1));
            workRule = workRules.findVersionsOf(new WorkRuleSeriesId(seriesId)).getFirst();
        }
        return workRule;
    }

    private WorkRule workRule;
}
