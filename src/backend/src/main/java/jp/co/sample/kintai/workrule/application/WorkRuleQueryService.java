package jp.co.sample.kintai.workrule.application;

import java.io.Serial;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sample.kintai.shared.application.AccessDeniedException;
import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.EmployeeVisibility;
import jp.co.sample.kintai.shared.domain.Requester;
import jp.co.sample.kintai.shared.domain.Role;
import jp.co.sample.kintai.workrule.domain.CompanyCalendarRepository;
import jp.co.sample.kintai.workrule.domain.DayType;
import jp.co.sample.kintai.workrule.domain.RegisteredCalendar;
import jp.co.sample.kintai.workrule.domain.WorkRule;
import jp.co.sample.kintai.workrule.domain.WorkRuleAssignment;
import jp.co.sample.kintai.workrule.domain.WorkRuleRepository;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeries;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesId;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesRepository;

/**
 * 就業規則とカレンダーの参照（API 設計書 2・3）。
 *
 * <p><strong>変更と分けて置く。</strong>
 * 変更（{@link WorkRuleMasterService}）は締め状態と給与連携の分母を守る責務を持ち、
 * 参照はそれを一切持たない。同じクラスに混ぜると、
 * 参照の経路にも締めの検査があるかのように読める。
 *
 * <p><strong>版の解決を SQL で書き直さない。</strong>
 * 「その日に有効な版」は行を読んでドメインに決めさせる。
 * 2 か所に実装があると、どちらが正しいかを 2 か所で保つことになる
 * （CLAUDE.md 落とし穴 13）。
 */
@Service
@Transactional(readOnly = true)
public class WorkRuleQueryService {

    /** 一度に照会できる日数の上限。年度の登録が目的なので 3 年で足りる。 */
    private static final long MAX_PERIOD_DAYS = 1096;

    private final WorkRuleSeriesRepository series;
    private final WorkRuleRepository workRules;
    private final CompanyCalendarRepository calendar;
    private final EmployeeVisibility visibility;
    private final Clock clock;

    public WorkRuleQueryService(WorkRuleSeriesRepository series, WorkRuleRepository workRules,
                                CompanyCalendarRepository calendar,
                                EmployeeVisibility visibility, Clock clock) {
        this.series = series;
        this.workRules = workRules;
        this.calendar = calendar;
        this.visibility = visibility;
        this.clock = clock;
    }

    /** 系列の一覧。廃止済みも含める（過去の勤怠が指しているため）。 */
    public List<WorkRuleSeries> 系列の一覧(Requester requester) {
        requireHumanResources(requester);
        return series.findAll();
    }

    /**
     * 系列の詳細と版の履歴。
     *
     * <p>版は<strong>開始日の順</strong>に並べる。改定の順序がそのまま読める。
     */
    public WorkRuleDetail 系列の詳細(Requester requester, WorkRuleSeriesId seriesId) {
        requireHumanResources(requester);
        WorkRuleSeries found = series.findById(seriesId)
                .orElseThrow(() -> new WorkRuleMasterService
                        .WorkRuleSeriesNotFoundException(seriesId));
        return new WorkRuleDetail(found, workRules.findVersionsOf(seriesId));
    }

    /**
     * 指定日に有効な版。
     *
     * <p><strong>版が無いことは正常に起こりうる。</strong>
     * 年度の途中で新設した系列は、それ以前の日に版を持たない（落とし穴 131）。
     * だから空を返し、呼び出し側が 404 に写す。
     */
    public WorkRule 指定日に有効な版(Requester requester, WorkRuleSeriesId seriesId,
                               LocalDate date) {
        requireHumanResources(requester);
        if (series.findById(seriesId).isEmpty()) {
            throw new WorkRuleMasterService.WorkRuleSeriesNotFoundException(seriesId);
        }
        return workRules.findVersionsOf(seriesId).stream()
                .filter(version -> version.validPeriod().contains(date))
                .findFirst()
                .orElseThrow(() -> new EffectiveVersionNotFoundException(seriesId, date));
    }

    /**
     * 社員への適用履歴。
     *
     * <p><strong>本人も見られる</strong>（API 設計書 1 の一覧）。
     * 自分がどの制度で働いているかは、自分の労働条件そのものである。
     * 判定は {@code EmployeeVisibility} に任せる。
     * ロールだけで拒むと、上長が部下の制度を確かめられない。
     */
    public List<WorkRuleAssignment> 適用履歴(Requester requester, EmployeeId employeeId) {
        if (!visibility.canView(requester, employeeId, LocalDate.now(clock))) {
            throw new AccessDeniedException();
        }
        return series.findAssignments(employeeId);
    }

    /**
     * 就業規則が引けない在籍者（API 設計書 2.4）。
     *
     * <p><strong>この一覧が空でないと、その社員の勤怠は計算できない。</strong>
     * 「在籍者全員に規則が適用されている」ことは DB では守れないので、
     * 人事が気づける経路をここに置く。
     *
     * <p>社員番号も氏名も返さない。{@code employee} が所有する概念であり、
     * ここに混ぜると {@code workrule} が持っていない情報の提供者になる。
     */
    public UnassignedEmployees 規則の無い在籍者(Requester requester, Optional<LocalDate> date) {
        requireHumanResources(requester);
        LocalDate on = date.orElseGet(() -> LocalDate.now(clock));
        return new UnassignedEmployees(on, series.findEmployeesWithoutRuleOn(on));
    }

    /**
     * 会社カレンダー（API 設計書 3.1）。
     *
     * <p><strong>未登録の日も {@code WORKDAY} として配列に含める。</strong>
     * 「配列に無い日は所定労働日」という暗黙の規則を受け取る側に持たせない。
     */
    public CalendarView カレンダー(Requester requester, LocalDate from, LocalDate toExclusive) {
        // ★ ロールで拒まない。所定労働日は全社員の労働条件である
        if (requester == null) {
            throw new AccessDeniedException();
        }
        DateRange period = requireValidPeriod(from, toExclusive);
        RegisteredCalendar registered = new RegisteredCalendar(calendar.findByPeriod(period));
        List<CalendarDay> days = period.dates()
                .map(date -> new CalendarDay(date, registered.dayTypeOf(date)))
                .toList();
        return new CalendarView(period, days, registered.workdayCountIn(period));
    }

    /**
     * 期間の妥当性。
     *
     * <p><strong>{@code DateRange} の compact constructor に任せない。</strong>
     * 利用者が送る値なので、素通しすると理由の載らない 500 になる（落とし穴 105）。
     */
    private static DateRange requireValidPeriod(LocalDate from, LocalDate toExclusive) {
        if (from == null || toExclusive == null) {
            throw new InvalidPeriodException("期間の指定がありません");
        }
        if (!from.isBefore(toExclusive)) {
            throw new InvalidPeriodException(
                    "期間の終わりは始まりより後でなければなりません: %s 〜 %s"
                            .formatted(from, toExclusive));
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(from, toExclusive) > MAX_PERIOD_DAYS) {
            throw new InvalidPeriodException(
                    "一度に照会できるのは %d 日までです".formatted(MAX_PERIOD_DAYS));
        }
        return new DateRange(from, toExclusive);
    }

    private static void requireHumanResources(Requester requester) {
        if (!requester.has(Role.HR)) {
            throw new AccessDeniedException();
        }
    }

    /** 系列と、その版の履歴。 */
    public record WorkRuleDetail(WorkRuleSeries series, List<WorkRule> revisions) {
    }

    /** 規則の無い在籍者。基準日を添えて返す（既定は当日）。 */
    public record UnassignedEmployees(LocalDate date, List<EmployeeId> employeeIds) {
    }

    /** カレンダーの 1 日。 */
    public record CalendarDay(LocalDate date, DayType dayType) {
    }

    /** 期間のカレンダー。 */
    public record CalendarView(DateRange period, List<CalendarDay> days, int workdayCount) {
    }

    /**
     * 系列はあるが、その日に有効な版が無い。
     *
     * <p><strong>「系列が無い」と同じエラーにしない。</strong>
     * 前者は綴りの誤りで、後者は<strong>まだ効いていない</strong>という業務上の事実である。
     * 同じ 404 でも、利用者がすることがまったく違う。
     */
    public static final class EffectiveVersionNotFoundException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        EffectiveVersionNotFoundException(WorkRuleSeriesId id, LocalDate date) {
            super("%s に有効な版がありません: %s".formatted(date, id.value()));
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:work-rule-version-not-effective";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.NOT_FOUND;
        }

        @Override
        public String title() {
            return "その日に有効な就業規則がありません";
        }
    }

    /** 期間の指定が不正。 */
    public static final class InvalidPeriodException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        InvalidPeriodException(String message) {
            super(message);
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:invalid-period";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.RULE_VIOLATION;
        }

        @Override
        public String title() {
            return "期間の指定が不正です";
        }
    }
}
