package jp.co.sample.kintai.attendance.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.convention.TestBean;

import jp.co.sample.kintai.employee.domain.Email;
import jp.co.sample.kintai.employee.domain.Employee;
import jp.co.sample.kintai.employee.domain.EmployeeNumber;
import jp.co.sample.kintai.employee.domain.EmployeeRepository;
import jp.co.sample.kintai.shared.domain.BusinessZone;
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
 * 現在の勤務状態と打刻の一覧（IT-ATT-29〜35・BR-02・BR-09）。
 *
 * <p><strong>画面はここが返す {@code availableActions} だけでボタンを出し分ける。</strong>
 * 状態機械（BR-02）を画面に複製すると、休憩の扱いを変えたときに 2 か所を直すことになる
 * （[画面設計書 1.1 の原則 1]）。
 *
 * <p>時計を固定する。「いまの勤務日」は {@code Clock} から決まるので、
 * 実時刻に依存させるとテストが日付をまたいだ瞬間に落ちる。
 */
@DisplayName("現在の勤務状態（BR-02）")
class CurrentAttendanceApiTest extends WebIntegrationTestBase {

    private static final LocalDate HIRED = LocalDate.of(2026, 4, 1);
    /** 2026-04-06 は月曜。 */
    private static final LocalDate MON = LocalDate.of(2026, 4, 6);

    @TestBean
    private Clock clock;

    static Clock clock() {
        return Clock.fixed(MON.atTime(14, 0).atZone(BusinessZone.ID).toInstant(),
                BusinessZone.ID);
    }

    @Autowired
    private EmployeeRepository employees;
    @Autowired
    private WorkRuleSeriesRepository series;
    @Autowired
    private WorkRuleRepository workRules;

    private EmployeeId taro;
    private EmployeeId hanako;

    @BeforeEach
    void setUpMasterData() {
        taro = hire("E0001", "山田 太郎");
        hanako = hire("E0002", "鈴木 花子");
        var standard = new WorkRuleSeriesId(UUID.randomUUID());
        series.save(WorkRuleSeries.active(standard, "標準勤務"));
        workRules.save(WorkRules.versionOf(standard, HIRED, WorkRules.fixed(),
                Duration.ofHours(8), NightWindow.STANDARD));
        series.assign(taro, standard, HIRED);
    }

    /** 打刻が 1 件も無い日は「未出勤」。押せるのは出勤だけである。 */
    @Test
    @DisplayName("IT-ATT-29 打刻していなければ未出勤で、押せるのは出勤だけ")
    void notStarted() throws Exception {
        current()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workDate").value("2026-04-06"))
                .andExpect(jsonPath("$.status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.availableActions").value("CLOCK_IN"))
                .andExpect(jsonPath("$.punches").isEmpty());
    }

    @Test
    @DisplayName("IT-ATT-30 出勤すると休憩開始と退勤が押せるようになる")
    void working() throws Exception {
        punch("CLOCK_IN", "2026-04-06T09:00:00");

        current()
                .andExpect(jsonPath("$.status").value("WORKING"))
                .andExpect(jsonPath("$.availableActions[0]").value("BREAK_START"))
                .andExpect(jsonPath("$.availableActions[1]").value("CLOCK_OUT"))
                .andExpect(jsonPath("$.availableActions.length()").value(2))
                .andExpect(jsonPath("$.punches.length()").value(1))
                .andExpect(jsonPath("$.punches[0].type").value("CLOCK_IN"))
                .andExpect(jsonPath("$.punches[0].occurredAt").value("2026-04-06T09:00:00"));
    }

    @Test
    @DisplayName("IT-ATT-31 休憩中は休憩終了だけが押せる")
    void onBreak() throws Exception {
        punch("CLOCK_IN", "2026-04-06T09:00:00");
        punch("BREAK_START", "2026-04-06T12:00:00");

        current()
                .andExpect(jsonPath("$.status").value("ON_BREAK"))
                .andExpect(jsonPath("$.availableActions").value("BREAK_END"));
    }

    /**
     * <strong>退勤済は押せるボタンが無い。</strong>
     * 出勤は必ず新しい勤務日を始めるので、同じ日に出勤し直すことはできない（落とし穴 68）。
     */
    @Test
    @DisplayName("IT-ATT-32 退勤済は押せるボタンが無い")
    void finished() throws Exception {
        punch("CLOCK_IN", "2026-04-06T09:00:00");
        punch("CLOCK_OUT", "2026-04-06T13:00:00");

        current()
                .andExpect(jsonPath("$.status").value("FINISHED"))
                .andExpect(jsonPath("$.availableActions").isEmpty());
    }

    /**
     * <strong>退勤を打ち忘れた日は「いまの勤務日」のままである。</strong>
     * 打刻は開いている勤務日へ追記されるので、画面もその日を見せなければならない
     * （[03 API設計書 2.2]）。
     */
    @Test
    @DisplayName("IT-ATT-33 前日の退勤を打ち忘れていると、その日が現在の勤務日になる")
    void openWorkDateFromYesterday() throws Exception {
        punch("CLOCK_IN", "2026-04-05T09:00:00");

        current()
                .andExpect(jsonPath("$.workDate")
                        .value("2026-04-05"))
                .andExpect(jsonPath("$.status").value("WORKING"));
    }

    /**
     * <strong>打刻の識別子を返す。</strong>
     * 訂正申請の取消は識別子で対象を指すので、返さないと利用者は対象を選べず、
     * 実在しない識別子を送って外部キー違反にするしかない（落とし穴 66）。
     */
    @Test
    @DisplayName("IT-ATT-34 勤務日の打刻を識別子つきで引ける")
    void recordedPunchesCarryIds() throws Exception {
        punch("CLOCK_IN", "2026-04-06T09:00:00");
        punch("BREAK_START", "2026-04-06T12:00:00");

        String body = mockMvc.perform(get("/api/employees/{id}/time-clocks", taro.value())
                        .param("workDate", "2026-04-06")
                        .with(as(taro, "E0001", Role.EMPLOYEE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].type").value("CLOCK_IN"))
                .andExpect(jsonPath("$[0].source").value("WEB"))
                .andExpect(jsonPath("$[0].revoked").value(false))
                .andExpect(jsonPath("$[0].revocation").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        String id = objectMapper.readTree(body).get(0).get("id").asString();
        assertThat(UUID.fromString(id)).as("訂正申請の targetEventId に渡せる形").isNotNull();
    }

    /** 打刻は本人の操作である。他人の現在の状態は見られない（要件 4.1）。 */
    @Test
    @DisplayName("IT-ATT-35 他人の現在の勤務状態は見られない")
    void othersAreForbidden() throws Exception {
        mockMvc.perform(get("/api/employees/{id}/attendances/current", taro.value())
                        .with(as(hanako, "E0002", Role.EMPLOYEE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:kintai:error:forbidden"));
    }

    private org.springframework.test.web.servlet.ResultActions current() throws Exception {
        return mockMvc.perform(get("/api/employees/{id}/attendances/current", taro.value())
                .with(as(taro, "E0001", Role.EMPLOYEE)));
    }

    private void punch(String type, String at) throws Exception {
        mockMvc.perform(post("/api/employees/{id}/time-clocks", taro.value())
                        .with(as(taro, "E0001", Role.EMPLOYEE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"%s\",\"occurredAt\":\"%s\"}"
                                .formatted(type, at)))
                .andExpect(status().isCreated());
    }

    private EmployeeId hire(String number, String name) {
        var id = new EmployeeId(UUID.randomUUID());
        employees.save(new Employee(id, new EmployeeNumber(number), name,
                new Email(number.toLowerCase() + "@example.com"), HIRED,
                Optional.empty(), Set.of(Role.EMPLOYEE)));
        return id;
    }
}
