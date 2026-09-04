package jp.co.sample.kintai.scenario;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import jp.co.sample.kintai.employee.domain.Assignment;
import jp.co.sample.kintai.employee.domain.AssignmentRepository;
import jp.co.sample.kintai.employee.domain.Department;
import jp.co.sample.kintai.employee.domain.DepartmentCode;
import jp.co.sample.kintai.employee.domain.DepartmentId;
import jp.co.sample.kintai.employee.domain.DepartmentRepository;
import jp.co.sample.kintai.employee.domain.Email;
import jp.co.sample.kintai.employee.domain.Employee;
import jp.co.sample.kintai.employee.domain.EmployeeNumber;
import jp.co.sample.kintai.employee.domain.EmployeeRepository;
import jp.co.sample.kintai.employee.domain.Managership;
import jp.co.sample.kintai.employee.domain.ManagershipRepository;
import jp.co.sample.kintai.shared.domain.BusinessZone;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.Role;
import jp.co.sample.kintai.support.WebIntegrationTestBase;
import jp.co.sample.kintai.support.WorkRules;
import jp.co.sample.kintai.workrule.domain.CompanyCalendarRepository;
import jp.co.sample.kintai.workrule.domain.DayType;
import jp.co.sample.kintai.workrule.domain.NightWindow;
import jp.co.sample.kintai.workrule.domain.WorkRule;
import jp.co.sample.kintai.workrule.domain.WorkRuleRepository;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeries;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesId;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesRepository;

/**
 * シナリオテストの土台。
 *
 * <p><strong>業務の言葉でヘルパを書く。</strong>
 * シナリオは業務の記述であり、読めることが第一である
 * （結合テスト仕様書 5.2）。
 *
 * <p><strong>HTTP を通す（未決事項 #2 の判断）。</strong>
 * アプリケーションサービスを直接呼ぶと 1 シナリオが 1 トランザクションになり、
 * 実際の業務が複数のリクエストにまたがることを再現できない。
 * 締めの排他や楽観ロックは、まさにリクエストの境界で効くものである。
 */
abstract class ScenarioTestBase extends WebIntegrationTestBase {

    @Autowired
    protected EmployeeRepository 社員リポジトリ;
    @Autowired
    protected DepartmentRepository 部署リポジトリ;
    @Autowired
    protected AssignmentRepository 所属リポジトリ;
    @Autowired
    protected ManagershipRepository 部署長リポジトリ;
    @Autowired
    protected WorkRuleSeriesRepository 就業規則系列リポジトリ;
    @Autowired
    protected WorkRuleRepository 就業規則リポジトリ;
    @Autowired
    protected CompanyCalendarRepository カレンダーリポジトリ;

    /**
     * 認証済みの利用者。シナリオでは「誰として操作するか」を必ず明示する。
     *
     * <p><strong>型の名前だけは日本語にしない。</strong>
     * 型名はそのまま {@code .class} のファイル名になり、
     * locale が POSIX / C の環境（CI のコンテナ）で書き出しに失敗する
     * （CLAUDE.md 落とし穴 29 と同型）。メソッド名は日本語でよい。
     */
    protected record Actor(EmployeeId id, String 社員番号, Role... ロール) {

        Role[] roles() {
            return ロール;
        }
    }

    // ---------------------------------------------------------------- 準備

    protected Actor 社員を登録する(String 社員番号, String 氏名, LocalDate 入社日,
                              Optional<LocalDate> 退職日, Role... ロール) {
        var id = new EmployeeId(UUID.randomUUID());
        社員リポジトリ.save(new Employee(id, new EmployeeNumber(社員番号), 氏名,
                new Email(社員番号.toLowerCase() + "@example.com"), 入社日, 退職日,
                Set.of(ロール)));
        return new Actor(id, 社員番号, ロール);
    }

    protected Actor 社員を登録する(String 社員番号, String 氏名, LocalDate 入社日,
                              Role... ロール) {
        return 社員を登録する(社員番号, 氏名, 入社日, Optional.empty(), ロール);
    }

    protected DepartmentId 部署を作る(String コード, String 名前) {
        var id = new DepartmentId(UUID.randomUUID());
        部署リポジトリ.save(Department.root(id, new DepartmentCode(コード), 名前));
        return id;
    }

    protected DepartmentId 部署を作る(String コード, String 名前, DepartmentId 親) {
        var id = new DepartmentId(UUID.randomUUID());
        部署リポジトリ.save(Department.under(id, new DepartmentCode(コード), 名前, 親));
        return id;
    }

    protected void 所属させる(Actor 社員, DepartmentId 部署, LocalDate 開始日) {
        所属リポジトリ.save(Assignment.startingAt(社員.id(), 部署, 開始日));
    }

    protected void 部署長にする(DepartmentId 部署, Actor 社員, LocalDate 開始日) {
        部署長リポジトリ.save(Managership.startingAt(部署, 社員.id(), 開始日));
    }

    /** 固定時間制（1 日 8 時間）の就業規則を適用する。 */
    protected WorkRuleSeriesId 固定時間制を適用する(Actor 社員, LocalDate 適用開始日) {
        var 系列 = new WorkRuleSeriesId(UUID.randomUUID());
        就業規則系列リポジトリ.save(WorkRuleSeries.active(系列, "標準勤務"));
        就業規則リポジトリ.save(WorkRules.versionOf(系列, 適用開始日, WorkRules.fixed(),
                Duration.ofHours(8), NightWindow.STANDARD));
        就業規則系列リポジトリ.assign(社員.id(), 系列, 適用開始日);
        return 系列;
    }

    /** フレックスタイム制（清算期間 1 か月）の就業規則を適用する。 */
    protected WorkRuleSeriesId フレックスを適用する(Actor 社員, LocalDate 適用開始日) {
        var 系列 = new WorkRuleSeriesId(UUID.randomUUID());
        就業規則系列リポジトリ.save(WorkRuleSeries.active(系列, "フレックス勤務"));
        就業規則リポジトリ.save(WorkRules.versionOf(系列, 適用開始日, WorkRules.flex(),
                Duration.ofHours(8), NightWindow.STANDARD));
        就業規則系列リポジトリ.assign(社員.id(), 系列, 適用開始日);
        return 系列;
    }

    /**
     * 既存の系列に新しい版を足す（改定）。
     *
     * <p><strong>適用行（{@code work_rule_assignments}）は書き換えない。</strong>
     * 社員は系列を指しているので、版を足しても適用は切れない（ADR 0003）。
     *
     * <p>旧版は改定日で閉じる。閉じないと
     * {@code work_rules_no_overlapping_versions} が重なりを拒否する。
     */
    protected void 就業規則を改定する(Actor 社員, WorkRuleSeriesId 系列,
                                LocalDate 改定日, WorkRule 新しい版) {
        WorkRule 旧版 = 就業規則リポジトリ.findEffective(社員.id(), 改定日.minusDays(1))
                .orElseThrow();
        就業規則リポジトリ.save(new WorkRule(旧版.id(), 旧版.seriesId(),
                new jp.co.sample.kintai.shared.domain.DateRange(
                        旧版.validPeriod().from(), 改定日),
                旧版.workingTimeSystem(), 旧版.statutoryDailyWorkingTime(),
                旧版.statutoryWeeklyWorkingTime(), 旧版.nightWindow(),
                旧版.premiumRates()));
        就業規則リポジトリ.save(新しい版);
    }

    /** その月の土日を休日として登録し、平日は所定労働日にする。 */
    protected void 暦を用意する(YearMonth 月) {
        暦を用意する(月.atDay(1), 月.plusMonths(1).atDay(1));
    }

    protected void 暦を用意する(LocalDate 開始, LocalDate 終了排他) {
        for (LocalDate d = 開始; d.isBefore(終了排他); d = d.plusDays(1)) {
            switch (d.getDayOfWeek()) {
                case SUNDAY -> カレンダーリポジトリ.save(d, DayType.LEGAL_HOLIDAY, "法定休日");
                case SATURDAY -> カレンダーリポジトリ.save(d, DayType.NON_LEGAL_HOLIDAY,
                        "所定休日");
                default -> カレンダーリポジトリ.save(d, DayType.WORKDAY, "所定労働日");
            }
        }
    }

    // ---------------------------------------------------------------- 打刻

    protected ResultActions 打刻する(Actor 社員, String 種別, LocalDateTime 時刻)
            throws Exception {
        return mockMvc.perform(認証つき(
                post("/api/employees/{id}/time-clocks", 社員.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"%s\",\"occurredAt\":\"%s\"}"
                                .formatted(種別, 時刻)), 社員));
    }

    /** 9:00 出勤・17:00 退勤の 1 日を打刻する（休憩なし・実働 8 時間）。 */
    protected void 定時で働く(Actor 社員, LocalDate 勤務日) throws Exception {
        打刻する(社員, "CLOCK_IN", 勤務日.atTime(9, 0)).andExpect(status().isCreated());
        打刻する(社員, "CLOCK_OUT", 勤務日.atTime(17, 0)).andExpect(status().isCreated());
    }

    /** その月の所定労働日をすべて定時で働く。 */
    protected void 月を通して定時で働く(Actor 社員, YearMonth 月) throws Exception {
        月を通して定時で働く(社員, 月.atDay(1), 月.plusMonths(1).atDay(1));
    }

    protected void 月を通して定時で働く(Actor 社員, LocalDate 開始, LocalDate 終了排他)
            throws Exception {
        for (LocalDate d = 開始; d.isBefore(終了排他); d = d.plusDays(1)) {
            if (所定労働日である(d)) {
                定時で働く(社員, d);
            }
        }
    }

    protected boolean 所定労働日である(LocalDate 日) {
        return switch (日.getDayOfWeek()) {
            case SATURDAY, SUNDAY -> false;
            default -> true;
        };
    }

    // ------------------------------------------------------ 提出・承認・締め

    protected ResultActions 提出する(Actor 実行者, Actor 対象, YearMonth 月)
            throws Exception {
        return 月次勤怠を遷移させる("submission", 実行者, 対象, 月, null);
    }

    protected ResultActions 代理提出する(Actor 実行者, Actor 対象, YearMonth 月,
                                   String 理由) throws Exception {
        return 月次勤怠を遷移させる("submission", 実行者, 対象, 月,
                "\"comment\":\"%s\"".formatted(理由));
    }

    protected ResultActions 承認する(Actor 実行者, Actor 対象, YearMonth 月)
            throws Exception {
        return 月次勤怠を遷移させる("approval", 実行者, 対象, 月, null);
    }

    protected ResultActions 差し戻す(Actor 実行者, Actor 対象, YearMonth 月,
                                String 理由) throws Exception {
        return 月次勤怠を遷移させる("rejection", 実行者, 対象, 月,
                "\"reason\":\"%s\"".formatted(理由));
    }

    protected ResultActions 締める(Actor 実行者, Actor 対象, YearMonth 月)
            throws Exception {
        return 月次勤怠を遷移させる("closure", 実行者, 対象, 月, null);
    }

    private ResultActions 月次勤怠を遷移させる(String 操作, Actor 実行者, Actor 対象,
                                       YearMonth 月, String 追加の項目)
            throws Exception {
        String body = 追加の項目 == null
                ? "{\"version\":%d}".formatted(月次勤怠の版(対象, 月))
                : "{%s,\"version\":%d}".formatted(追加の項目, 月次勤怠の版(対象, 月));
        return mockMvc.perform(認証つき(post(
                "/api/employees/{id}/monthly-attendances/{month}/" + 操作,
                対象.id().value(), 月.toString())
                .contentType(MediaType.APPLICATION_JSON).content(body), 実行者));
    }

    // ------------------------------------------------------------ 訂正申請

    protected String 訂正を申請する(Actor 社員, LocalDate 勤務日, String 理由,
                              String 項目) throws Exception {
        var response = mockMvc.perform(認証つき(
                post("/api/employees/{id}/correction-requests", 社員.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workDate":"%s","reason":"%s","items":[%s]}
                                """.formatted(勤務日, 理由, 項目)), 社員))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(
                response.getResponse().getContentAsString(), "$.id");
    }

    protected ResultActions 訂正を承認する(Actor 実行者, String 申請ID) throws Exception {
        return mockMvc.perform(認証つき(
                post("/api/correction-requests/{id}/approval", 申請ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":%d}".formatted(訂正申請の版(申請ID))),
                実行者));
    }

    /** 打刻を 1 件足す訂正の項目。 */
    protected static String 打刻を足す(String 種別, LocalDateTime 時刻) {
        return """
                {"action":"ADD","eventType":"%s","occurredAt":"%s"}
                """.formatted(種別, 時刻);
    }

    // -------------------------------------------------------------- 参照

    protected ResultActions 月次勤怠を見る(Actor 実行者, Actor 対象, YearMonth 月)
            throws Exception {
        return mockMvc.perform(認証つき(get(
                "/api/employees/{id}/monthly-attendances/{month}",
                対象.id().value(), 月.toString()), 実行者));
    }

    protected String 月次勤怠の状態(Actor 対象, YearMonth 月) {
        return jdbc.queryForList("""
                SELECT status FROM monthly_attendances
                WHERE employee_id = ? AND target_month = ?
                """, String.class, 対象.id().value(), 月.atDay(1))
                .stream().findFirst().orElse("DRAFT");
    }

    protected long 月次勤怠の版(Actor 対象, YearMonth 月) {
        return jdbc.queryForList("""
                SELECT version FROM monthly_attendances
                WHERE employee_id = ? AND target_month = ?
                """, Long.class, 対象.id().value(), 月.atDay(1))
                .stream().findFirst().orElse(0L);
    }

    protected long 訂正申請の版(String 申請ID) {
        return jdbc.queryForObject(
                "SELECT version FROM time_clock_correction_requests WHERE id = ?",
                Long.class, UUID.fromString(申請ID));
    }

    /** 証跡。<strong>状態が変わっただけでは足りない。誰が何をしたかを見る。</strong> */
    protected List<String> 証跡の種類(Actor 対象, YearMonth 月) {
        return jdbc.queryForList("""
                SELECT e.event_kind FROM approval_events e
                  JOIN monthly_attendances m ON m.id = e.monthly_attendance_id
                 WHERE m.employee_id = ? AND m.target_month = ?
                 ORDER BY e.occurred_at, e.created_at
                """, String.class, 対象.id().value(), 月.atDay(1));
    }

    protected Integer 日次の実労働分(Actor 対象, LocalDate 勤務日) {
        return jdbc.queryForList("""
                SELECT working_minutes FROM daily_attendances
                WHERE employee_id = ? AND work_date = ?
                """, Integer.class, 対象.id().value(), 勤務日)
                .stream().findFirst().orElse(null);
    }

    protected Integer 月次の項目(Actor 対象, YearMonth 月, String 列) {
        return jdbc.queryForList(
                "SELECT %s FROM monthly_settlements WHERE employee_id = ? AND target_month = ?"
                        .formatted(列), Integer.class, 対象.id().value(), 月.atDay(1))
                .stream().findFirst().orElse(null);
    }

    protected Boolean 月次の真偽(Actor 対象, YearMonth 月, String 列) {
        return jdbc.queryForList(
                "SELECT %s FROM monthly_settlements WHERE employee_id = ? AND target_month = ?"
                        .formatted(列), Boolean.class, 対象.id().value(), 月.atDay(1))
                .stream().findFirst().orElse(null);
    }

    private MockHttpServletRequestBuilder 認証つき(MockHttpServletRequestBuilder request,
                                              Actor 実行者) {
        return request.with(as(実行者.id(), 実行者.社員番号(), 実行者.roles()));
    }

    /** 会社基準の壁掛け時計で、その日時に時計を固定する。 */
    protected static Clock 時計を(LocalDate 日, int 時, int 分) {
        return Clock.fixed(日.atTime(時, 分).atZone(BusinessZone.ID).toInstant(),
                BusinessZone.ID);
    }
}
