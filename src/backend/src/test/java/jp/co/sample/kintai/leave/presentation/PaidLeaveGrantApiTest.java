package jp.co.sample.kintai.leave.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.ResultActions;

import jp.co.sample.kintai.attendance.domain.DailyAttendanceRepository;
import jp.co.sample.kintai.employee.domain.Email;
import jp.co.sample.kintai.employee.domain.Employee;
import jp.co.sample.kintai.employee.domain.EmployeeNumber;
import jp.co.sample.kintai.employee.domain.EmployeeRepository;
import jp.co.sample.kintai.shared.domain.BusinessZone;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.Role;
import jp.co.sample.kintai.support.DailyAttendances;
import jp.co.sample.kintai.support.WebIntegrationTestBase;
import jp.co.sample.kintai.support.WorkRules;
import jp.co.sample.kintai.workrule.domain.CompanyCalendar;
import jp.co.sample.kintai.workrule.domain.CompanyCalendarRepository;
import jp.co.sample.kintai.workrule.domain.DayType;
import jp.co.sample.kintai.workrule.domain.NightWindow;
import jp.co.sample.kintai.workrule.domain.WorkRule;
import jp.co.sample.kintai.workrule.domain.WorkRuleRepository;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeries;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesId;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesRepository;

/**
 * 年次有給休暇の付与の API（IT-LV-58〜64・90〜92）。
 *
 * <p>時計は 2026-11-10 に固定する。付与日の到来を検査するので、
 * 実時刻で回すとテストが通るかどうかが実行した日で変わる。
 */
@DisplayName("年次有給休暇の付与 API")
class PaidLeaveGrantApiTest extends WebIntegrationTestBase {

    private static final LocalDate HIRED = LocalDate.of(2026, 4, 1);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    /** 0 回目の付与日（入社から 6 か月後）。 */
    private static final LocalDate FIRST_GRANT = LocalDate.of(2026, 10, 1);

    @TestBean
    private Clock clock;

    static Clock clock() {
        return Clock.fixed(TODAY.atTime(10, 0).atZone(BusinessZone.ID).toInstant(),
                BusinessZone.ID);
    }

    @Autowired
    private EmployeeRepository employees;
    @Autowired
    private WorkRuleSeriesRepository series;
    @Autowired
    private WorkRuleRepository workRules;
    @Autowired
    private CompanyCalendarRepository calendarRepository;
    @Autowired
    private CompanyCalendar calendar;
    @Autowired
    private DailyAttendanceRepository dailyAttendances;

    private EmployeeId hr;
    private WorkRuleSeriesId standard;

    @BeforeEach
    void setUpOrganization() {
        hr = hire("E0900", "人事 花子", Optional.empty());
        standard = new WorkRuleSeriesId(UUID.randomUUID());
        series.save(WorkRuleSeries.active(standard, "標準勤務"));
        workRules.save(WorkRules.versionOf(standard, LocalDate.of(2026, 1, 1),
                WorkRules.fixed(), Duration.ofHours(8), NightWindow.STANDARD));
    }

    @Nested
    @DisplayName("付与の実行")
    class Grant {

        @Test
        @DisplayName("IT-LV-58 人事が付与を実行すると、付与された社員が返る")
        void grants() throws Exception {
            EmployeeId yamada = hireWorking("E0001", HIRED, Optional.empty(), 150, 150);

            grant(FIRST_GRANT)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.asOf").value("2026-10-01"))
                    .andExpect(jsonPath("$.granted[?(@.employeeId=='%s')].days"
                            .formatted(yamada.value())).value(10));
        }

        /** 冪等。同じ基準日で 2 回実行しても二重に付与しない。 */
        @Test
        @DisplayName("IT-LV-59 同じ基準日で 2 回実行すると 2 回目は skipped に入る")
        void idempotent() throws Exception {
            EmployeeId yamada = hireWorking("E0001", HIRED, Optional.empty(), 150, 150);
            grant(FIRST_GRANT).andExpect(status().isOk());

            grant(FIRST_GRANT)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.granted[?(@.employeeId=='%s')]"
                            .formatted(yamada.value())).isEmpty())
                    .andExpect(jsonPath("$.skipped[?(@.employeeId=='%s')].reason"
                            .formatted(yamada.value())).value("already-granted"));
        }

        /**
         * <strong>{@code withheld} と {@code skipped} を分ける。</strong>
         * 前者は法どおりの不付与、後者は既に処理済みという運用上の事実であり、
         * 人事が取るべき行動が違う。
         */
        @Test
        @DisplayName("IT-LV-60 8 割未達の社員は withheld に入る")
        void withheld() throws Exception {
            // 所定労働日 150 日のうち出勤 100 日。8 割（120 日）に届かない
            EmployeeId taro = hireWorking("E0002", HIRED, Optional.empty(), 150, 100);

            grant(FIRST_GRANT)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.granted[?(@.employeeId=='%s')]"
                            .formatted(taro.value())).isEmpty())
                    .andExpect(jsonPath("$.skipped[?(@.employeeId=='%s')]"
                            .formatted(taro.value())).isEmpty())
                    .andExpect(jsonPath(
                            "$.withheld[?(@.employeeId=='%s')].attendanceRate.attendedDays"
                                    .formatted(taro.value())).value(100));
        }

        /** 依頼そのものの不備は例外へ。全員を skipped にすると権限不足に気づけない。 */
        @Test
        @DisplayName("IT-LV-61 人事でない社員は付与を実行できない")
        void notHumanResources() throws Exception {
            EmployeeId yamada = hireWorking("E0001", HIRED, Optional.empty(), 150, 150);

            mockMvc.perform(post("/api/paid-leave-grants")
                            .with(as(yamada, "E0001", Role.EMPLOYEE))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"asOf\":\"2026-10-01\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("urn:kintai:error:forbidden"));
        }

        /** バッチが動かなかった日があっても、次の実行で追いつく（落とし穴 26）。 */
        @Test
        @DisplayName("IT-LV-62 基準日を先に進めると、到来済みの未処理分がすべて作られる")
        void catchesUp() throws Exception {
            EmployeeId yamada = hireWorking("E0001", HIRED, Optional.empty(), 150, 150);

            grant(FIRST_GRANT.plusYears(1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.granted[?(@.employeeId=='%s')].grantedOn"
                            .formatted(yamada.value()))
                            .value(org.hamcrest.Matchers.containsInAnyOrder(
                                    "2026-10-01", "2027-10-01")));
        }

        /**
         * <strong>退職者を除かないと毎年 20 日が積み上がる。</strong>
         * しかも退職者の算定期間は在籍期間で絞ると全労働日 0 になり、
         * 出勤率の判定を必ず通る（落とし穴 92）。
         */
        @Test
        @DisplayName("IT-LV-92 退職者は付与の対象に入らない")
        void retiredIsExcluded() throws Exception {
            EmployeeId jiro = hire("E0003", "退職 次郎",
                    Optional.of(FIRST_GRANT.minusDays(1)));

            grant(FIRST_GRANT)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.granted[?(@.employeeId=='%s')]"
                            .formatted(jiro.value())).isEmpty())
                    .andExpect(jsonPath("$.withheld[?(@.employeeId=='%s')]"
                            .formatted(jiro.value())).isEmpty());
        }
    }

    @Nested
    @DisplayName("再判定")
    class Reassessment {

        @Test
        @DisplayName("IT-LV-63 不付与を再判定して 8 割を満たせば付与に変わる")
        void becomesGranted() throws Exception {
            EmployeeId taro = hireWorking("E0002", HIRED, Optional.empty(), 150, 100);
            grant(FIRST_GRANT).andExpect(status().isOk());

            // 実績が増えたことにする（訂正申請で欠勤が出勤に直った場合と同じ）
            workMore(taro, 100, 120);

            reassess(taro, FIRST_GRANT, "{}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.granted").value(true))
                    .andExpect(jsonPath("$.days").value(10));
        }

        /**
         * 休業を出勤扱いとして申告する（BR-14）。
         *
         * <p><strong>打刻を足して実績を整えさせない。</strong>
         * 働いていない日の労働時間が一次証拠として残り、割増賃金の計算に入る。
         */
        @Test
        @DisplayName("IT-LV-90 出勤扱いの日数を申告すると付与に変わる")
        void deemedAttendance() throws Exception {
            EmployeeId hanako = hireWorking("E0004", HIRED, Optional.empty(), 150, 100);
            grant(FIRST_GRANT).andExpect(status().isOk());

            reassess(hanako, FIRST_GRANT, """
                    {"deemedAttendedDays":20,
                     "deemedReason":"産前産後休業（2026-05-01〜2026-07-31）"}
                    """)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.granted").value(true))
                    .andExpect(jsonPath("$.attendanceRate.deemedAttendedDays").value(20))
                    .andExpect(jsonPath("$.attendanceRate.deemedReason")
                            .value("産前産後休業（2026-05-01〜2026-07-31）"));
        }

        /** 根拠の無い出勤扱いを作らせない。 */
        @Test
        @DisplayName("IT-LV-91 出勤扱いを申告して理由が無いと受け付けない")
        void deemedReasonRequired() throws Exception {
            EmployeeId hanako = hireWorking("E0004", HIRED, Optional.empty(), 150, 100);
            grant(FIRST_GRANT).andExpect(status().isOk());

            reassess(hanako, FIRST_GRANT, "{\"deemedAttendedDays\":20}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:validation-failed"));
        }

        /**
         * <strong>付与済みは対象外。</strong>
         * 一度発生した年休の権利を実績の訂正で消すのは労働者に不利であり、
         * 消化済みなら辻褄も合わなくなる。
         */
        @Test
        @DisplayName("IT-LV-64 付与済みの付与は再判定できない")
        void alreadyGranted() throws Exception {
            EmployeeId yamada = hireWorking("E0001", HIRED, Optional.empty(), 150, 150);
            grant(FIRST_GRANT).andExpect(status().isOk());

            reassess(yamada, FIRST_GRANT, "{}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:grant-already-granted"));
        }
    }

    private ResultActions grant(LocalDate asOf) throws Exception {
        return mockMvc.perform(post("/api/paid-leave-grants")
                .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"asOf\":\"%s\"}".formatted(asOf)));
    }

    private ResultActions reassess(EmployeeId employeeId, LocalDate grantedOn,
                                   String body) throws Exception {
        return mockMvc.perform(post(
                "/api/employees/{id}/paid-leave-grants/{grantedOn}/reassessment",
                employeeId.value(), grantedOn)
                .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private EmployeeId hire(String number, String name, Optional<LocalDate> retiredOn) {
        var id = new EmployeeId(UUID.randomUUID());
        employees.save(new Employee(id, new EmployeeNumber(number), name,
                new Email(number.toLowerCase() + "@example.com"), HIRED, retiredOn,
                Set.of(Role.EMPLOYEE)));
        return id;
    }

    /**
     * 出勤率を指定して社員を作る。
     *
     * <p><strong>暦日区分を登録しない日は所定労働日になる</strong>ので、
     * 183 日の算定期間をそのまま使うと全労働日 183・出勤 0 で必ず不付与になる。
     * 算定期間を所定休日で埋め、{@code workdays} 日だけを所定労働日に戻す。
     */
    private EmployeeId hireWorking(String number, LocalDate hiredOn,
                                   Optional<LocalDate> retiredOn, int workdays,
                                   int attended) {
        EmployeeId id = hire(number, number + " の人", retiredOn);
        series.assign(id, standard, hiredOn);
        for (LocalDate date = hiredOn; date.isBefore(hiredOn.plusYears(2));
                date = date.plusDays(1)) {
            calendarRepository.save(date, DayType.NON_LEGAL_HOLIDAY, "所定休日");
        }
        for (int i = 0; i < workdays; i++) {
            calendarRepository.save(hiredOn.plusDays(i), DayType.WORKDAY, "所定労働日");
        }
        workMore(id, 0, attended);
        return id;
    }

    /**
     * {@code from} 日目から {@code to} 日目まで出勤した事実を残す。
     *
     * <p><strong>本番の計算を通して作る。</strong> 行を手で書くと内訳が空になり、
     * 「内訳の合計 = 実労働時間」という不変条件に弾かれる（落とし穴 37・55）。
     */
    private void workMore(EmployeeId id, int from, int to) {
        WorkRule rule = workRules.findEffective(id, HIRED).orElseThrow();
        for (int i = from; i < to; i++) {
            LocalDate date = HIRED.plusDays(i);
            dailyAttendances.save(id,
                    new DailyAttendances(calendar).fixedDay(date, Duration.ofHours(8)),
                    rule.id());
        }
    }
}
