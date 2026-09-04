package jp.co.sample.kintai.attendance.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * 打刻と日次勤怠の API（IT-API-01〜12）。
 *
 * <p><strong>API から DB までを通す。</strong>
 * コントローラだけを切り出してリポジトリを差し替えると、
 * 層をまたいだ欠陥（番兵の写像・タイムゾーンの往復）を素通りさせる。
 */
@DisplayName("打刻と日次勤怠の API")
class TimeClockApiTest extends WebIntegrationTestBase {

    private static final LocalDate HIRED = LocalDate.of(2026, 4, 1);
    /** 2026-04-06 は月曜。 */
    private static final LocalDate MON = LocalDate.of(2026, 4, 6);

    @Autowired
    private EmployeeRepository employees;
    @Autowired
    private WorkRuleSeriesRepository series;
    @Autowired
    private WorkRuleRepository workRules;

    private EmployeeId taro;

    /** 太郎として認証済みにする。CSRF トークンも載る。 */
    private org.springframework.test.web.servlet.request.RequestPostProcessor asTaro() {
        return as(taro, "E0001", Role.EMPLOYEE);
    }

    @BeforeEach
    void setUpMasterData() {
        taro = new EmployeeId(UUID.randomUUID());
        employees.save(new Employee(taro, new EmployeeNumber("E0001"), "山田 太郎",
                new Email("e0001@example.com"), HIRED, Optional.empty(),
                Set.of(Role.EMPLOYEE)));
        var standard = new WorkRuleSeriesId(UUID.randomUUID());
        series.save(WorkRuleSeries.active(standard, "標準勤務"));
        workRules.save(WorkRules.versionOf(standard, HIRED, WorkRules.fixed(),
                Duration.ofHours(8), NightWindow.STANDARD));
        series.assign(taro, standard, HIRED);
    }

    private org.springframework.test.web.servlet.ResultActions punch(String type, String at)
            throws Exception {
        String body = at == null
                ? "{\"type\":\"%s\"}".formatted(type)
                : "{\"type\":\"%s\",\"occurredAt\":\"%s\"}".formatted(type, at);
        return mockMvc.perform(post("/api/employees/{id}/time-clocks", taro.value())
                .with(asTaro())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Nested
    @DisplayName("打刻")
    class Punch {

        @Test
        @DisplayName("IT-API-01 出勤打刻は 201 を返し、退勤前は集計を返さない")
        void clockIn() throws Exception {
            punch("CLOCK_IN", "2026-04-06T09:00:00")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.workDate").value("2026-04-06"))
                    .andExpect(jsonPath("$.calculationStatus").value("NOT_CLOSED"))
                    .andExpect(jsonPath("$.attendance").doesNotExist());
        }

        /** 秒まで受け付ける。分へそろえるのはサーバの仕事（BR-01）。 */
        @Test
        @DisplayName("IT-API-02 秒を含む打刻を受け付け、労働者に有利な向きに丸める")
        void secondsAreRounded() throws Exception {
            punch("CLOCK_IN", "2026-04-06T08:59:59").andExpect(status().isCreated());

            punch("CLOCK_OUT", "2026-04-06T18:00:01")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.calculationStatus").value("CALCULATED"))
                    // 08:59 – 18:01 の 542 分。開始は切り捨て、終了は切り上げ
                    .andExpect(jsonPath("$.attendance.workingMinutes").value(542));
        }

        @Test
        @DisplayName("IT-API-03 退勤まで打刻すると集計が返る")
        void clockOutReturnsAttendance() throws Exception {
            punch("CLOCK_IN", "2026-04-06T09:00:00").andExpect(status().isCreated());
            punch("BREAK_START", "2026-04-06T12:00:00").andExpect(status().isCreated());
            punch("BREAK_END", "2026-04-06T13:00:00").andExpect(status().isCreated());

            punch("CLOCK_OUT", "2026-04-06T20:00:00")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.calculationStatus").value("CALCULATED"))
                    .andExpect(jsonPath("$.attendance.workingMinutes").value(600))
                    .andExpect(jsonPath("$.attendance.breakMinutes").value(60))
                    .andExpect(jsonPath("$.attendance.baseMinutes").value(480))
                    .andExpect(jsonPath("$.attendance.overtimeBeyondStatutoryMinutes")
                            .value(120))
                    .andExpect(jsonPath("$.attendance.slices").isArray());
        }

        /**
         * <strong>勤務日は打刻した暦日と一致しない</strong>（BR-03）。
         * 翌 02:00 の退勤は「月曜の勤務」に追記されなければならない。
         * 暦日で振り分けると、出勤の無い火曜に退勤だけが残る。
         */
        @Test
        @DisplayName("IT-API-04 日をまたぐ退勤は出勤した日の勤務に追記される")
        void overnightBelongsToTheClockInDate() throws Exception {
            punch("CLOCK_IN", "2026-04-06T20:00:00").andExpect(status().isCreated());

            punch("CLOCK_OUT", "2026-04-07T02:00:00")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.workDate").value("2026-04-06"))
                    .andExpect(jsonPath("$.attendance.workingMinutes").value(360))
                    .andExpect(jsonPath("$.attendance.nightMinutes").value(240));

            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM time_clock_events WHERE work_date = ?",
                    Integer.class, MON))
                    .as("2 件とも月曜の勤務日に記録される").isEqualTo(2);
        }

        /** 応答の日時にオフセットを含めない（API 共通仕様 1.1）。 */
        @Test
        @DisplayName("IT-API-05 応答の日時は壁掛け時計時刻でオフセットを含まない")
        void noOffsetInResponse() throws Exception {
            punch("CLOCK_IN", "2026-04-06T09:00:00");

            String body = punch("CLOCK_OUT", "2026-04-06T18:00:00")
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).contains("\"startedAt\":\"2026-04-06T09:00:00\"");
            assertThat(body).doesNotContain("+09:00");
            assertThat(body).doesNotContain("Z\"");
        }
    }

    @Nested
    @DisplayName("エラー応答（RFC 9457）")
    class Errors {

        /**
         * 打刻の順序が状態機械に反する。
         * <strong>打刻を足しても直らないので 422 で訂正申請へ案内する。</strong>
         */
        @Test
        @DisplayName("IT-API-06 二重の出勤は 422 invalid-time-clock-sequence")
        void doubleClockIn() throws Exception {
            punch("CLOCK_IN", "2026-04-06T09:00:00").andExpect(status().isCreated());

            punch("CLOCK_IN", "2026-04-06T09:30:00")
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:invalid-time-clock-sequence"))
                    .andExpect(jsonPath("$.title").value("打刻の順序が不正です"))
                    .andExpect(jsonPath("$.detail").value(
                            org.hamcrest.Matchers.containsString("勤務中 の状態では行えません")));

            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM time_clock_events", Integer.class))
                    .as("拒否された打刻は記録されない").isEqualTo(1);
        }

        /**
         * 「まだ退勤していない」は<strong>打刻の時点では正常</strong>なので、
         * 打刻の API には現れない。労働時間の確定を求めたときに 409 で返る。
         */
        @Test
        @DisplayName("IT-API-07 未退勤の日の日次勤怠は 404（計算されていない）")
        void attendanceNotCalculated() throws Exception {
            punch("CLOCK_IN", "2026-04-06T09:00:00").andExpect(status().isCreated());

            mockMvc.perform(get("/api/employees/{id}/attendances/{date}",
                            taro.value(), "2026-04-06").with(asTaro()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:resource-not-found"))
                    .andExpect(jsonPath("$.title").value("日次勤怠が見つかりません"));
        }

        @Test
        @DisplayName("IT-API-08 打刻種別を欠くと 400 validation-failed")
        void missingType() throws Exception {
            mockMvc.perform(post("/api/employees/{id}/time-clocks", taro.value())
                            .with(asTaro())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"occurredAt\":\"2026-04-06T09:00:00\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:validation-failed"))
                    .andExpect(jsonPath("$.errors[0].field").value("type"));
        }

        /**
         * <strong>就業規則が無くても打刻は記録される。</strong>
         * 計算側の都合で一次証拠の記録を止めない（CLAUDE.md 落とし穴 19）。
         */
        @Test
        @DisplayName("IT-API-09 就業規則が無い社員でも打刻は成功し、計算だけ行われない")
        void withoutWorkRule() throws Exception {
            var jiro = new EmployeeId(UUID.randomUUID());
            employees.save(new Employee(jiro, new EmployeeNumber("E0003"), "鈴木 次郎",
                    new Email("e0003@example.com"), HIRED, Optional.empty(),
                    Set.of(Role.EMPLOYEE)));

            mockMvc.perform(post("/api/employees/{id}/time-clocks", jiro.value())
                            .with(as(jiro, "E0003", Role.EMPLOYEE))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"CLOCK_IN\","
                                    + "\"occurredAt\":\"2026-04-06T09:00:00\"}"))
                    .andExpect(status().isCreated());

            mockMvc.perform(post("/api/employees/{id}/time-clocks", jiro.value())
                            .with(as(jiro, "E0003", Role.EMPLOYEE))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"CLOCK_OUT\","
                                    + "\"occurredAt\":\"2026-04-06T18:00:00\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.calculationStatus")
                            .value("WORK_RULE_NOT_FOUND"))
                    .andExpect(jsonPath("$.attendance").doesNotExist());

            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM time_clock_events WHERE employee_id = ?",
                    Integer.class, jiro.value()))
                    .as("打刻は 2 件とも記録されている").isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("日次勤怠の参照")
    class Query {

        @Test
        @DisplayName("IT-API-10 指定日の日次勤怠を内訳つきで返す")
        void getOneDay() throws Exception {
            punch("CLOCK_IN", "2026-04-06T20:00:00");
            punch("CLOCK_OUT", "2026-04-07T02:00:00");

            mockMvc.perform(get("/api/employees/{id}/attendances/{date}",
                            taro.value(), "2026-04-06").with(asTaro()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.workDate").value("2026-04-06"))
                    .andExpect(jsonPath("$.dayType").value("WORKDAY"))
                    .andExpect(jsonPath("$.workingTimeSystem").value("FIXED"))
                    .andExpect(jsonPath("$.nightMinutes").value(240))
                    // 暦日境界で分かれるので、内訳は 3 区間になる。
                    // 月 20:00–22:00（属性なし）/ 月 22:00–24:00（深夜）/ 火 00:00–02:00（深夜）
                    .andExpect(jsonPath("$.slices.length()").value(3))
                    .andExpect(jsonPath("$.slices[0].calendarDate").value("2026-04-06"))
                    .andExpect(jsonPath("$.slices[0].premiums.length()").value(0))
                    .andExpect(jsonPath("$.slices[1].calendarDate").value("2026-04-06"))
                    .andExpect(jsonPath("$.slices[1].premiums[0]").value("NIGHT"))
                    .andExpect(jsonPath("$.slices[2].calendarDate").value("2026-04-07"))
                    .andExpect(jsonPath("$.slices[2].premiums[0]").value("NIGHT"))
                    .andExpect(jsonPath("$.slices[2].minutes").value(120));
        }

        @Test
        @DisplayName("IT-API-11 月次の一覧は計算済みの日だけを返す")
        void listByMonth() throws Exception {
            punch("CLOCK_IN", "2026-04-06T09:00:00");
            punch("CLOCK_OUT", "2026-04-06T18:00:00");
            // 翌日は出勤したまま退勤していないので、計算されない
            punch("CLOCK_IN", "2026-04-08T09:00:00");

            mockMvc.perform(get("/api/employees/{id}/attendances", taro.value())
                            .with(asTaro())
                            .param("month", "2026-04"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].workDate").value("2026-04-06"));
        }

        /**
         * 月末日が漏れないこと。期間を {@code atEndOfMonth()} で組み立てると
         * 半開区間の上限がずれて末日が落ちる。
         */
        @Test
        @DisplayName("IT-API-12 月末日の勤怠も月次の一覧に現れる")
        void lastDayOfMonth() throws Exception {
            punch("CLOCK_IN", "2026-04-30T09:00:00");
            punch("CLOCK_OUT", "2026-04-30T18:00:00");

            mockMvc.perform(get("/api/employees/{id}/attendances", taro.value())
                            .with(asTaro())
                            .param("month", "2026-04"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].workDate").value("2026-04-30"));
        }

        @Test
        @DisplayName("計算された勤怠が無い月は空の配列を返す")
        void emptyMonth() throws Exception {
            mockMvc.perform(get("/api/employees/{id}/attendances", taro.value())
                            .with(asTaro())
                            .param("month", "2026-05"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }
    }
}
