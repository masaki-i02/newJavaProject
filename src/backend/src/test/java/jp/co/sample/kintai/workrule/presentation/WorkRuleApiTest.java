package jp.co.sample.kintai.workrule.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import jp.co.sample.kintai.employee.domain.Email;
import jp.co.sample.kintai.employee.domain.Employee;
import jp.co.sample.kintai.employee.domain.EmployeeNumber;
import jp.co.sample.kintai.employee.domain.EmployeeRepository;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.Role;
import jp.co.sample.kintai.support.WebIntegrationTestBase;
import jp.co.sample.kintai.support.WorkRules;
import jp.co.sample.kintai.workrule.domain.NightWindow;
import jp.co.sample.kintai.workrule.domain.WorkRuleRepository;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeries;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesId;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesRepository;

/**
 * 就業規則の登録・改定・参照（IT-WR-24〜41・IT-CAL-13〜15）。
 *
 * <p><strong>この API は設計書にあって実装が無かった。</strong>
 * 就業規則は DB へ直接 INSERT するしか作れず、
 * UC-03「就業規則を登録・改定する」が API から実行できなかった
 * （CLAUDE.md 落とし穴 138）。テストは「存在する API」しか検査しないので、
 * 1000 件あっても 1 件も落ちない。
 */
@DisplayName("就業規則の API")
class WorkRuleApiTest extends WebIntegrationTestBase {

    private static final LocalDate HIRED = LocalDate.of(2026, 1, 1);

    @Autowired
    private EmployeeRepository employees;
    @Autowired
    private WorkRuleSeriesRepository series;
    @Autowired
    private WorkRuleRepository workRules;

    private EmployeeId hr;
    private EmployeeId taro;

    private org.springframework.test.web.servlet.request.RequestPostProcessor asHr() {
        return as(hr, "E0900", Role.EMPLOYEE, Role.HR);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor asTaro() {
        return as(taro, "E0001", Role.EMPLOYEE);
    }

    @BeforeEach
    void setUpEmployees() {
        hr = new EmployeeId(UUID.randomUUID());
        employees.save(new Employee(hr, new EmployeeNumber("E0900"), "人事 花子",
                new Email("e0900@example.com"), HIRED, Optional.empty(),
                Set.of(Role.EMPLOYEE, Role.HR)));
        taro = new EmployeeId(UUID.randomUUID());
        employees.save(new Employee(taro, new EmployeeNumber("E0001"), "山田 太郎",
                new Email("e0001@example.com"), HIRED, Optional.empty(),
                Set.of(Role.EMPLOYEE)));
    }

    private static String fixedBody(String name, String validFrom, String start, String end) {
        return """
                {"name": "%s", "validFrom": "%s",
                 "system": {"fixedTime": {"scheduledStart": "%s", "scheduledEnd": "%s",
                                          "scheduledBreakMinutes": 60}}}
                """.formatted(name, validFrom, start, end);
    }

    private org.springframework.test.web.servlet.ResultActions register(String body)
            throws Exception {
        return mockMvc.perform(post("/api/work-rules").with(asHr())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Nested
    @DisplayName("登録")
    class Registration {

        @Test
        @DisplayName("IT-WR-24 就業規則を登録すると系列と初版ができる")
        void createsSeriesAndFirstRevision() throws Exception {
            String body = register(fixedBody("標準勤務", "2026-04-01", "09:00", "18:00"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.seriesId").exists())
                    .andExpect(jsonPath("$.workRuleId").exists())
                    // ★ 版は保存された実際の値。手で書いた定数を返すと、
                    //   DB の既定と食い違ったときに次の改定が必ず 409 になる
                    .andExpect(jsonPath("$.version").value(0))
                    .andReturn().getResponse().getContentAsString();

            String seriesId = new tools.jackson.databind.ObjectMapper()
                    .readTree(body).get("seriesId").asString();
            mockMvc.perform(get("/api/work-rules/{id}", seriesId).with(asHr()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("標準勤務"))
                    .andExpect(jsonPath("$.revisions.length()").value(1))
                    .andExpect(jsonPath("$.revisions[0].workingTimeSystem").value("FIXED"))
                    .andExpect(jsonPath("$.revisions[0].fixedTime.scheduledWorkingMinutes")
                            .value(480))
                    // ★ 使わないほうのキーは出さない（null も出さない）
                    .andExpect(jsonPath("$.revisions[0].flextime").doesNotExist())
                    // ★ 割増率は文字列。数値にすると受け手の言語で丸め誤差が入る
                    .andExpect(jsonPath("$.revisions[0].premiumRates.night").value("0.250"));
        }

        @Test
        @DisplayName("IT-WR-25 人事でなければ登録できない")
        void requiresHumanResources() throws Exception {
            mockMvc.perform(post("/api/work-rules").with(asTaro())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(fixedBody("標準勤務", "2026-04-01", "09:00", "18:00")))
                    .andExpect(status().isForbidden());
        }

        /**
         * <strong>500 にしない。</strong>
         * 法定を超える所定は人事が登録画面から踏める誤りであり、業務エラーである。
         */
        @Test
        @DisplayName("IT-WR-26 所定が法定 8 時間を超えると 422")
        void scheduledOverStatutory() throws Exception {
            register(fixedBody("長時間", "2026-04-01", "09:00", "19:00"))
                    .andExpect(status().isUnprocessableContent());
        }

        @Test
        @DisplayName("IT-WR-27 制度を 2 つとも指定すると 422")
        void bothSystems() throws Exception {
            register("""
                    {"name": "両方", "validFrom": "2026-04-01",
                     "system": {"fixedTime": {"scheduledStart": "09:00",
                                              "scheduledEnd": "18:00",
                                              "scheduledBreakMinutes": 60},
                                "flextime": {"flexibleStart": "07:00", "flexibleEnd": "22:00",
                                             "coreStart": "11:00", "coreEnd": "15:00",
                                             "standardDailyMinutes": 480}}}
                    """)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:invalid-work-rule-request"));
        }

        @Test
        @DisplayName("IT-WR-28 制度を 1 つも指定しないと 422")
        void noSystem() throws Exception {
            register("""
                    {"name": "無し", "validFrom": "2026-04-01", "system": {}}
                    """)
                    .andExpect(status().isUnprocessableContent());
        }

        /** 名称が空なら 400。ドメインの compact constructor へ落とさない。 */
        @Test
        @DisplayName("IT-WR-29 名称が空だと 400")
        void blankName() throws Exception {
            register(fixedBody("", "2026-04-01", "09:00", "18:00"))
                    .andExpect(status().isBadRequest());
        }

        /**
         * <strong>法定労働時間 0 分を 500 にしない。</strong>
         * ドメインは {@code IllegalArgumentException} を投げるので、
         * 素通しすると理由の載らない 500 になる（落とし穴 105）。
         */
        /**
         * <strong>法定労働時間は入力ではない。</strong>
         * 送られても無視し、常に法定の 8 時間・40 時間で登録する。
         *
         * <p>受け取ると 40 時間未満の規則を作れてしまい、
         * その社員の月次清算は DDL の 2400 直書きに弾かれて
         * 永久に保存できなくなる（IT-API-46・落とし穴 126）。
         */
        @Test
        @DisplayName("IT-WR-44 法定労働時間を送っても無視され、法定の値で登録される")
        void statutoryWorkingTimeIsNotAnInput() throws Exception {
            String body = register("""
                    {"name": "短い週", "validFrom": "2026-10-01",
                     "statutoryDailyMinutes": 400,
                     "statutoryWeeklyMinutes": 2340,
                     "system": {"fixedTime": {"scheduledStart": "09:00",
                                              "scheduledEnd": "18:00",
                                              "scheduledBreakMinutes": 60}}}
                    """)
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();

            String seriesId = new tools.jackson.databind.ObjectMapper()
                    .readTree(body).get("seriesId").asString();
            mockMvc.perform(get("/api/work-rules/{id}", seriesId).with(asHr()))
                    .andExpect(jsonPath("$.revisions[0].statutoryWeeklyMinutes").value(2400))
                    .andExpect(jsonPath("$.revisions[0].statutoryDailyMinutes").value(480));
        }

    }

    @Nested
    @DisplayName("改定")
    class Revision {

        private WorkRuleSeriesId standard;

        @BeforeEach
        void setUpSeries() {
            standard = new WorkRuleSeriesId(UUID.randomUUID());
            series.save(WorkRuleSeries.active(standard, "標準勤務"));
            workRules.save(WorkRules.versionOf(standard, LocalDate.of(2026, 1, 1),
                    WorkRules.fixed(), Duration.ofHours(8), NightWindow.STANDARD));
        }

        private org.springframework.test.web.servlet.ResultActions revise(long version,
                String validFrom, String end) throws Exception {
            return mockMvc.perform(post("/api/work-rules/{id}/revisions", standard.value())
                    .with(asHr()).contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"version": %d, "validFrom": "%s",
                             "system": {"fixedTime": {"scheduledStart": "09:00",
                                                      "scheduledEnd": "%s",
                                                      "scheduledBreakMinutes": 60}}}
                            """.formatted(version, validFrom, end)));
        }

        /**
         * <strong>改定は既存の版を書き換えない。</strong>
         * 現行版を閉じて新しい版を足す。書き換えると過去の勤怠が
         * 当時とは違う規則で再計算される。
         */
        @Test
        @DisplayName("IT-WR-31 改定すると現行版が閉じて新しい版が増える")
        void closesCurrentAndAddsNew() throws Exception {
            revise(0, "2026-10-01", "17:30")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.version").value(1));

            mockMvc.perform(get("/api/work-rules/{id}", standard.value()).with(asHr()))
                    .andExpect(jsonPath("$.revisions.length()").value(2))
                    // 現行版は改定日で閉じる。半開区間なので同じ日から新版が効く
                    .andExpect(jsonPath("$.revisions[0].validToExclusive")
                            .value("2026-10-01"))
                    .andExpect(jsonPath("$.revisions[1].validFrom").value("2026-10-01"))
                    // 最新版は終わりを持たない
                    .andExpect(jsonPath("$.revisions[1].validToExclusive").doesNotExist())
                    .andExpect(jsonPath("$.revisions[1].fixedTime.scheduledWorkingMinutes")
                            .value(450));
        }

        @Test
        @DisplayName("IT-WR-32 版が一致しない改定は 409")
        void staleVersion() throws Exception {
            revise(99, "2026-10-01", "17:30")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:optimistic-lock-failure"));
        }

        /**
         * <strong>版が進まなければ行も書かれない。</strong>
         * 先に版を進めてから書くので、競合したときに
         * 片方だけ書かれた状態が残らない。
         */
        @Test
        @DisplayName("IT-WR-33 版が一致しないと版も行も増えない")
        void staleVersionWritesNothing() throws Exception {
            revise(99, "2026-10-01", "17:30").andExpect(status().isConflict());

            mockMvc.perform(get("/api/work-rules/{id}", standard.value()).with(asHr()))
                    .andExpect(jsonPath("$.version").value(0))
                    .andExpect(jsonPath("$.revisions.length()").value(1));
        }

        @Test
        @DisplayName("IT-WR-34 現行版より前の日付への改定は 409")
        void beforeCurrentVersion() throws Exception {
            revise(0, "2025-12-01", "17:30")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("urn:kintai:error:overlapping-period"));
        }

        /**
         * <strong>月の途中から所定を変えられない。</strong>
         * 月次清算は 1 か月を 1 つの版で計算するので、所定が月中で割れると
         * 所定総労働時間も不足時間も片方の版の値だけで求まる。
         *
         * <p>ここで拒まないと、その月は提出も承認も締めもできなくなり、
         * 規則を戻す以外に出口が無くなる（落とし穴 26・93）。
         */
        @Test
        @DisplayName("IT-WR-42 月中の改定で所定労働時間を変えると 422")
        void midMonthRevisionChangingScheduledTime() throws Exception {
            revise(0, "2026-10-15", "17:30")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:monthly-basis-changed-mid-month"));
        }

        /**
         * <strong>月中の改定そのものは禁じない。</strong>
         * 所定を変えなければ月次の計算は変わらないので、通す。
         * 一律に拒むと、深夜帯の誤りを月中に直せなくなる（IT-SCN-09）。
         */
        @Test
        @DisplayName("IT-WR-43 所定を変えない月中の改定は通る")
        void midMonthRevisionKeepingScheduledTime() throws Exception {
            revise(0, "2026-10-15", "18:00")
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("IT-WR-35 存在しない系列の改定は 404")
        void unknownSeries() throws Exception {
            mockMvc.perform(post("/api/work-rules/{id}/revisions", UUID.randomUUID())
                            .with(asHr()).contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"version": 0, "validFrom": "2026-10-01",
                                     "system": {"fixedTime": {"scheduledStart": "09:00",
                                                              "scheduledEnd": "17:30",
                                                              "scheduledBreakMinutes": 60}}}
                                    """))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("参照")
    class Queries {

        private WorkRuleSeriesId standard;

        @BeforeEach
        void setUpSeries() {
            standard = new WorkRuleSeriesId(UUID.randomUUID());
            series.save(WorkRuleSeries.active(standard, "標準勤務"));
            workRules.save(WorkRules.versionOf(standard, LocalDate.of(2026, 4, 1),
                    WorkRules.fixed(), Duration.ofHours(8), NightWindow.STANDARD));
        }

        @Test
        @DisplayName("IT-WR-36 一覧は版の履歴を含めない")
        void listOmitsRevisions() throws Exception {
            mockMvc.perform(get("/api/work-rules").with(asHr()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].name").value("標準勤務"))
                    .andExpect(jsonPath("$[0].revisions").doesNotExist());
        }

        @Test
        @DisplayName("IT-WR-37 指定日に有効な版を返す")
        void effective() throws Exception {
            mockMvc.perform(get("/api/work-rules/{id}/effective", standard.value())
                            .with(asHr()).param("date", "2026-05-01"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.validFrom").value("2026-04-01"));
        }

        /**
         * <strong>「系列が無い」と別のエラーにする。</strong>
         * 版が無いのは、年度途中に新設した系列では正常に起こりうる
         * （落とし穴 131）。綴りの誤りとは利用者がすることが違う。
         */
        @Test
        @DisplayName("IT-WR-38 版が始まる前の日付は 404 で、系列が無いのとは別の型")
        void notEffectiveYet() throws Exception {
            mockMvc.perform(get("/api/work-rules/{id}/effective", standard.value())
                            .with(asHr()).param("date", "2026-03-31"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:work-rule-version-not-effective"));

            mockMvc.perform(get("/api/work-rules/{id}/effective", UUID.randomUUID())
                            .with(asHr()).param("date", "2026-05-01"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value("urn:kintai:error:resource-not-found"));
        }

        /**
         * <strong>版を持たない日へ適用しない。</strong>
         *
         * <p>適用だけがあって版が無い日は「規則が引けない日」になる。
         * その社員はその月を提出できず（`work-rule-not-assigned`）、
         * しかも `unassigned` の一覧は<strong>適用の有無しか見ない</strong>ので
         * 正常に見える。気づくのは月末に提出しようとしたときである。
         */
        @Test
        @DisplayName("IT-WR-45 その日に有効な版が無い系列は適用できない")
        void assignBeforeFirstVersion() throws Exception {
            var 系列 = new WorkRuleSeriesId(UUID.randomUUID());
            series.save(WorkRuleSeries.active(系列, "10 月から"));
            workRules.save(WorkRules.versionOf(系列, LocalDate.of(2026, 10, 1),
                    WorkRules.fixed(), Duration.ofHours(8), NightWindow.STANDARD));

            mockMvc.perform(post("/api/employees/{id}/work-rule-assignments",
                            taro.value()).with(asHr())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"seriesId":"%s","validFrom":"2026-04-01"}
                                    """.formatted(系列.value())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:no-effective-work-rule-version"));
        }

        @Test
        @DisplayName("IT-WR-39 規則の無い在籍者を返す")
        void unassigned() throws Exception {
            series.assign(hr, standard, LocalDate.of(2026, 4, 1));

            mockMvc.perform(get("/api/work-rule-assignments/unassigned").with(asHr())
                            .param("date", "2026-05-01"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.date").value("2026-05-01"))
                    // 太郎には適用していないので現れる。人事は適用済みなので現れない
                    .andExpect(jsonPath("$.employeeIds")
                            .value(org.hamcrest.Matchers.hasItem(taro.value().toString())))
                    .andExpect(jsonPath("$.employeeIds")
                            .value(org.hamcrest.Matchers.not(
                                    org.hamcrest.Matchers.hasItem(hr.value().toString()))));
        }

        /** 適用履歴は本人も見られる。自分の労働条件そのものである。 */
        @Test
        @DisplayName("IT-WR-40 本人は自分の適用履歴を見られる")
        void ownAssignments() throws Exception {
            series.assign(taro, standard, LocalDate.of(2026, 4, 1));

            mockMvc.perform(get("/api/employees/{id}/work-rule-assignments", taro.value())
                            .with(asTaro()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].validFrom").value("2026-04-01"))
                    .andExpect(jsonPath("$[0].validToExclusive").doesNotExist());
        }

        @Test
        @DisplayName("IT-WR-41 一般社員は他人の適用履歴を見られない")
        void otherAssignments() throws Exception {
            mockMvc.perform(get("/api/employees/{id}/work-rule-assignments", hr.value())
                            .with(asTaro()))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("カレンダーの取得")
    class Calendars {

        /**
         * <strong>未登録の日も所定労働日として返す。</strong>
         * 「配列に無い日は所定労働日」という暗黙の規則を受け取る側に持たせない。
         */
        @Test
        @DisplayName("IT-CAL-13 未登録の日も WORKDAY として配列に含まれる")
        void includesUnregisteredDays() throws Exception {
            mockMvc.perform(get("/api/calendars").with(asTaro())
                            .param("from", "2026-05-01").param("toExclusive", "2026-05-04"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.days.length()").value(3))
                    .andExpect(jsonPath("$.days[0].dayType").value("WORKDAY"))
                    .andExpect(jsonPath("$.workdayCount").value(3));
        }

        @Test
        @DisplayName("IT-CAL-14 登録した暦日区分が反映され、所定労働日数から外れる")
        void reflectsRegisteredDays() throws Exception {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .put("/api/calendars/{date}", "2026-05-03").with(asHr())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"dayType\": \"LEGAL_HOLIDAY\", \"name\": \"憲法記念日\"}"))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/calendars").with(asTaro())
                            .param("from", "2026-05-01").param("toExclusive", "2026-05-04"))
                    .andExpect(jsonPath("$.days[2].dayType").value("LEGAL_HOLIDAY"))
                    // ★ 名称も返る。書き込みだけ受け付けて読み出せない状態にしない。
                    //   このテストは以前から憲法記念日を登録していたのに、
                    //   名称を読んでいなかったので気づけなかった（落とし穴 112）
                    .andExpect(jsonPath("$.days[2].name").value("憲法記念日"))
                    .andExpect(jsonPath("$.workdayCount").value(2));
        }

        /**
         * <strong>名称の無い日は項目ごと省く。</strong>
         * 空文字を入れると「名前が無い」と「名前が空」が同じ値になる（落とし穴 76）。
         */
        @Test
        @DisplayName("IT-CAL-16 名称の無い日は name の項目そのものが無い")
        void omitsMissingName() throws Exception {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .put("/api/calendars/{date}", "2026-05-02").with(asHr())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"dayType\": \"NON_LEGAL_HOLIDAY\"}"))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/calendars").with(asTaro())
                            .param("from", "2026-05-01").param("toExclusive", "2026-05-03"))
                    .andExpect(jsonPath("$.days[1].dayType").value("NON_LEGAL_HOLIDAY"))
                    .andExpect(jsonPath("$.days[1].name").doesNotExist());
        }

        /** 期間の逆転を 500 にしない（落とし穴 105）。 */
        @Test
        @DisplayName("IT-CAL-15 期間が逆転していると 422")
        void reversedPeriod() throws Exception {
            mockMvc.perform(get("/api/calendars").with(asTaro())
                            .param("from", "2026-06-01").param("toExclusive", "2026-05-01"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.type").value("urn:kintai:error:invalid-period"));
        }
    }
}
