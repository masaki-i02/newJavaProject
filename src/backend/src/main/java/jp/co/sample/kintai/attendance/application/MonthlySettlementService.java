package jp.co.sample.kintai.attendance.application;

import java.io.Serial;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sample.kintai.attendance.domain.DailyAttendance;
import jp.co.sample.kintai.attendance.domain.DailyAttendanceRepository;
import jp.co.sample.kintai.attendance.domain.TimeClockEventRepository;
import jp.co.sample.kintai.attendance.domain.monthly.MonthlyDayCounts;
import jp.co.sample.kintai.attendance.domain.monthly.MonthlySettlement;
import jp.co.sample.kintai.attendance.domain.monthly.MonthlySettlementCalculator;
import jp.co.sample.kintai.attendance.domain.monthly.MonthlySettlementRepository;
import jp.co.sample.kintai.attendance.domain.monthly.WeeklyOvertimeRule;
import jp.co.sample.kintai.employee.domain.Employee;
import jp.co.sample.kintai.employee.domain.EmployeeRepository;
import jp.co.sample.kintai.shared.application.AccessDeniedException;
import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.DetailedDomainException;
import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.EmployeeVisibility;
import jp.co.sample.kintai.shared.domain.MonthClosureQuery;
import jp.co.sample.kintai.shared.domain.PaidLeaveDays;
import jp.co.sample.kintai.shared.domain.Requester;
import jp.co.sample.kintai.shared.domain.Role;
import jp.co.sample.kintai.workrule.domain.CompanyCalendar;
import jp.co.sample.kintai.workrule.domain.DayType;
import jp.co.sample.kintai.workrule.domain.SettlementPeriod;
import jp.co.sample.kintai.workrule.domain.WorkRule;
import jp.co.sample.kintai.workrule.domain.WorkRuleRepository;
import jp.co.sample.kintai.workrule.domain.WorkingTimeSystemType;

/**
 * 月次清算のユースケース（BR-04 / BR-05 / BR-12）。
 *
 * <p>集める順序そのものが業務上の判断である。
 * <strong>日次勤怠は対象月ではなく週次判定の走査範囲で読む。</strong>
 * 対象月の中だけを読むと、月初の週に必要な前月の日が欠け、
 * 週 40 時間超と法定休日からの通算を取りこぼす。
 */
@Service
public class MonthlySettlementService {

    private final DailyAttendanceRepository dailyAttendances;
    private final TimeClockEventRepository timeClocks;
    private final MonthlySettlementRepository settlements;
    private final WorkRuleRepository workRules;
    private final EmployeeRepository employees;
    private final CompanyCalendar calendar;
    private final MonthClosureQuery monthClosure;
    private final EmployeeVisibility visibility;
    private final PaidLeaveDays paidLeaveDays;

    public MonthlySettlementService(DailyAttendanceRepository dailyAttendances,
                                    TimeClockEventRepository timeClocks,
                                    MonthlySettlementRepository settlements,
                                    WorkRuleRepository workRules,
                                    EmployeeRepository employees,
                                    CompanyCalendar calendar,
                                    MonthClosureQuery monthClosure,
                                    EmployeeVisibility visibility,
                                    PaidLeaveDays paidLeaveDays) {
        this.dailyAttendances = dailyAttendances;
        this.timeClocks = timeClocks;
        this.settlements = settlements;
        this.workRules = workRules;
        this.employees = employees;
        this.calendar = calendar;
        this.monthClosure = monthClosure;
        this.visibility = visibility;
        this.paidLeaveDays = paidLeaveDays;
    }

    /**
     * 清算して保存する。
     *
     * <p>適用する就業規則は<strong>清算期間の末日時点の版</strong>を使う。
     * 月中に改定された場合の日ごとの切り替えは未実装である（UT-BR05-19）。
     */
    @Transactional
    public MonthlySettlement settle(EmployeeId employeeId, YearMonth month) {
        SettlementPeriod period = periodOf(employeeId, month);
        MonthlySettlement settlement = calculate(employeeId, period);
        settlements.save(settlement);
        return settlement;
    }

    /** 清算期間を求める。在籍していない月はここで弾く。 */
    private SettlementPeriod periodOf(EmployeeId employeeId, YearMonth month) {
        Employee employee = employees.findById(employeeId)
                .orElseThrow(() -> new EmployeeNotFoundException(employeeId));
        return SettlementPeriod.of(month, employee.activePeriod())
                .orElseThrow(() -> new NotEmployedInMonthException(employeeId, month));
    }

    /**
     * 計算する。<strong>保存はしない。</strong>
     *
     * <p>提出・訂正の承認・人事の指示のどの契機でも、通る計算は同じである。
     * 契機ごとに計算を分けると、片方だけを直した状態が生まれる。
     */
    private MonthlySettlement calculate(EmployeeId employeeId, SettlementPeriod period) {
        LocalDate lastDay = period.period().toExclusive().minusDays(1);
        WorkRule workRule = workRules.findEffective(employeeId, lastDay)
                .orElseThrow(() -> new WorkRuleNotAssignedException(employeeId, lastDay));

        DateRange scanRange = WeeklyOvertimeRule.scanRangeFor(period.period());
        List<DailyAttendance> days = dailyAttendances.findByPeriod(employeeId, scanRange);
        Duration annualBefore =
                settlements.annualSubjectTimeBefore(employeeId, period.month());

        return new MonthlySettlementCalculator(calendar)
                .calculate(employeeId, period, days, workRule, annualBefore,
                        paidLeaveDaysIn(employeeId, period));
    }

    /**
     * 所定労働日数から除く年休の日数（BR-16）。
     *
     * <p><strong>3 つの条件をすべて課す。</strong> どれを落としても実害がある。
     *
     * <ul>
     *   <li><strong>清算期間（暦月 ∩ 在籍期間）の中</strong> …
     *       落とすと、月中入社・月中退職の月で在籍していない日の年休を引いてしまう</li>
     *   <li><strong>カレンダー上 {@code WORKDAY}</strong> …
     *       年休を承認したあとにカレンダーでその日を休日に変えると（未締めなら通る）、
     *       所定労働日数より年休の日数が多くなる。負の所定総は保存できないので
     *       業務エラーですらない 500 になる（CLAUDE.md 落とし穴 81 と同型）</li>
     *   <li><strong>承認済み</strong> … ポートがそう定義している。
     *       未処理・却下・取下げで所定総が変わってはいけない</li>
     * </ul>
     */
    private int paidLeaveDaysIn(EmployeeId employeeId, SettlementPeriod period) {
        return (int) paidLeaveDays.approvedOn(employeeId, period.period()).stream()
                .filter(date -> calendar.dayTypeOf(date) == DayType.WORKDAY)
                .count();
    }

    /**
     * 承認済みの年休の日なのに実労働がある日（BR-16 / 06 API設計書 3.5）。
     *
     * <p><strong>行を持たない。</strong> 承認済みの取得日と日次勤怠を突き合わせて導く
     * （落とし穴 39）。
     *
     * <p>この食い違いは、年休の承認後にその日へ打刻すると生まれる。
     * 所定総からその日が除かれるのに実労働も乗るので、
     * <strong>不足時間が最大 8 時間ぶん過少に出る一方で、社員は年休を 1 日失う</strong>
     * （落とし穴 97）。取り消せるのは締め前だけなので、
     * <strong>締め前に気づく経路</strong>として提出の応答に載せる。
     *
     * <p>判定を {@code approval} に写さない。承認済みの取得日も日次勤怠も
     * ここから引くものであり、2 か所に置くと片方が古くなる（落とし穴 67）。
     */
    @Transactional(readOnly = true)
    public List<LocalDate> workedOnPaidLeaveDates(EmployeeId employeeId, YearMonth month) {
        SettlementPeriod period = periodOf(employeeId, month);
        Set<LocalDate> leaveDates = paidLeaveDays.approvedOn(employeeId, period.period());
        if (leaveDates.isEmpty()) {
            return List.of();
        }
        return dailyAttendances.findByPeriod(employeeId, period.period()).stream()
                .filter(day -> leaveDates.contains(day.workDate()))
                .filter(day -> day.workingTime().compareTo(Duration.ZERO) > 0)
                .map(DailyAttendance::workDate)
                .sorted()
                .toList();
    }

    /**
     * 給与へ渡す日数（BR-18 の⑤⑥⑦⑧）。
     *
     * <p><strong>数え方をここに置く。</strong> 所定総労働時間は
     * 「（所定労働日数 − 年休の日数）× 1 日の所定」として {@code calculate} が求めており、
     * 呼び出し側が会社カレンダーを引いて数え直すと、
     * <strong>所定総と所定労働日数が食い違う</strong>（CLAUDE.md 落とし穴 67）。
     *
     * <p><strong>所定労働日数は年休を引く前を返す。</strong>
     * 給与側は日額（月給 ÷ 所定労働日数）で欠勤控除するので、
     * 控除後を渡すと年休を取った月ほど 1 日あたりの控除が大きくなる。
     *
     * <p>欠勤日数は<strong>引き算で導かせない。</strong>
     * 出勤日数は法定休日・所定休日の出勤を含むので、
     * 所定労働日数から引くと負になる月がある（落とし穴 23・51）。
     */
    @Transactional(readOnly = true)
    public MonthlyDayCounts dayCountsIn(EmployeeId employeeId, YearMonth month) {
        SettlementPeriod period = periodOf(employeeId, month);
        int scheduledDays = calendar.workdayCountIn(period.period());
        int leaveDays = paidLeaveDaysIn(employeeId, period);

        List<DailyAttendance> worked = dailyAttendances
                .findByPeriod(employeeId, period.period()).stream()
                .filter(day -> day.workingTime().compareTo(Duration.ZERO) > 0)
                .toList();
        int attendedDays = worked.size();
        // ★ 欠勤は所定労働日の話なので、休日の出勤を数に入れない
        int workedOnScheduledDays = (int) worked.stream()
                .filter(day -> calendar.dayTypeOf(day.workDate()) == DayType.WORKDAY)
                .count();
        // ★ 年休の日に出勤した月（落とし穴 97）では所定労働日を超えうるので 0 で止める
        int absentDays = Math.max(0, scheduledDays - leaveDays - workedOnScheduledDays);

        return new MonthlyDayCounts(scheduledDays, attendedDays, leaveDays, absentDays);
    }

    /**
     * その月に打刻が 1 件でもあるか（BR-18 の除外理由）。
     *
     * <p><strong>月次勤怠の行の有無で判定しない。</strong>
     * 行は提出のときに初めて作られるので、行が無いことは「下書き」を意味する
     * （[05 ドメインモデル設計書](../05_申請承認と締め/)）。
     * 行で判定すると、1 か月まるまる働いて提出していないだけの社員が
     * 「1 日も打刻が無い」と扱われる（落とし穴 120）。
     */
    @Transactional(readOnly = true)
    public boolean hasTimeClockIn(EmployeeId employeeId, YearMonth month) {
        SettlementPeriod period = periodOf(employeeId, month);
        return !timeClocks.findWorkDatesWithEvents(employeeId, period.period()).isEmpty();
    }

    /**
     * 提出の事前条件を満たすか（BR-18 の除外理由）。
     *
     * <p>{@link #requireCalculable(EmployeeId, YearMonth)} の真偽版である。
     * 例外を投げる側だけだと、給与連携が
     * <strong>除外の理由として扱うために例外を捕まえる</strong>ことになる。
     *
     * <p>判定そのものは写さない。同じ非公開メソッドを呼ぶ（落とし穴 67）。
     */
    @Transactional(readOnly = true)
    public boolean isCalculable(EmployeeId employeeId, YearMonth month) {
        SettlementPeriod period = periodOf(employeeId, month);
        if (!incompleteWorkDates(employeeId, period).isEmpty()) {
            return false;
        }
        // ★ 就業規則が 1 日でも欠けていると settle が落ちるので、提出もできない
        return workRules.findEffectiveByPeriod(employeeId, period.period()).size()
                == (int) period.period().days();
    }

    @Transactional(readOnly = true)
    public Optional<MonthlySettlement> find(EmployeeId employeeId, YearMonth month) {
        return settlements.find(employeeId, month);
    }

    /**
     * 閲覧範囲を確かめてから読む。
     *
     * <p>基準日は<strong>対象月の末日</strong>にそろえる。
     * 今日の組織で過去の月の可否を決めると、異動した部下の異動前の月を
     * 旧上長が見られなくなる。
     */
    @Transactional(readOnly = true)
    public Optional<MonthlySettlement> find(Requester requester, EmployeeId employeeId,
                                            YearMonth month) {
        if (!visibility.canView(requester, employeeId, month.atEndOfMonth())) {
            throw new AccessDeniedException();
        }
        return settlements.find(employeeId, month);
    }

    /** その月の版。楽観ロックのために画面へ返す。 */
    @Transactional(readOnly = true)
    public long currentVersion(EmployeeId employeeId, YearMonth month) {
        return settlements.currentVersion(employeeId, month);
    }

    /**
     * 人事の指示で計算し直す（API 設計書 3）。
     *
     * <p>{@link #settle} との違いは<strong>4 つの検査</strong>だけである。
     * 計算そのものは同じ経路を通る。人事が指示したときだけ別の計算をしてはならない。
     *
     * <ol>
     *   <li>締め済みの月は再計算しない（BR-10）</li>
     *   <li>版が一致しないと拒否する。画面に出ていない結果を上書きしない</li>
     *   <li>未計算の勤務日が残っていたら拒否する。1 日欠けると結果が過少になる</li>
     *   <li>月の途中で労働時間制度が変わっていたら拒否する</li>
     * </ol>
     */
    @Transactional
    public MonthlySettlement recalculate(Requester requester, EmployeeId employeeId,
                                         YearMonth month, long expectedVersion) {
        if (!requester.has(Role.HR)) {
            throw new AccessDeniedException();
        }
        if (monthClosure.isClosed(employeeId, month)) {
            throw new MonthAlreadyClosedException(month);
        }
        SettlementPeriod period = periodOf(employeeId, month);
        requireAllDaysCalculated(employeeId, period);
        requireSingleWorkingTimeSystem(employeeId, period);

        MonthlySettlement settlement = calculate(employeeId, period);
        settlements.save(settlement, expectedVersion);
        return settlement;
    }

    /**
     * 未計算の勤務日が残っていないか（提出の事前条件・BR-10）。
     *
     * <p><strong>提出も同じ判定を使う。</strong> 月次勤怠の提出は
     * 「その月の日次が出そろっている」ことを求めるが、その定義を
     * {@code approval} 側に写すと、片方だけが古くなる（CLAUDE.md 落とし穴 67）。
     */
    @Transactional(readOnly = true)
    public void requireCalculable(EmployeeId employeeId, YearMonth month) {
        requireAllDaysCalculated(employeeId, periodOf(employeeId, month));
    }

    /**
     * 未計算の勤務日が残っていないか。
     *
     * <p>探す範囲は<strong>清算期間そのものではなく、週次判定に必要な範囲</strong>である。
     * 月初の週は前月の日を含むので、清算期間の中だけを見ると
     * 前月の日が欠けたまま週 40 時間超を判定してしまう。
     *
     * <p><strong>どの日が欠けているかを返す。</strong>
     * 「未計算の日があります」だけでは、利用者はどこを直せばよいか分からない。
     */
    private void requireAllDaysCalculated(EmployeeId employeeId, SettlementPeriod period) {
        List<LocalDate> incomplete = incompleteWorkDates(employeeId, period);
        if (!incomplete.isEmpty()) {
            throw new DailyAttendanceIncompleteException(incomplete);
        }
    }

    /**
     * 打刻があるのに日次勤怠が無い勤務日。
     *
     * <p><strong>投げる側と真偽を返す側で、この 1 つを共有する。</strong>
     * 写すと、走査範囲の取り方を直したときに片方だけが古くなる（落とし穴 67）。
     */
    private List<LocalDate> incompleteWorkDates(EmployeeId employeeId,
                                                SettlementPeriod period) {
        DateRange scanRange = WeeklyOvertimeRule.scanRangeFor(period.period());
        Set<LocalDate> calculated = dailyAttendances.findByPeriod(employeeId, scanRange)
                .stream().map(DailyAttendance::workDate).collect(Collectors.toSet());
        return timeClocks.findWorkDatesWithEvents(employeeId, scanRange).stream()
                .filter(workDate -> !calculated.contains(workDate))
                .toList();
    }

    /**
     * 月の途中で労働時間制度が変わっていないか。
     *
     * <p><strong>「制度の変更」と「規則の改定」を区別する。</strong>
     * 就業規則の版が月中で変わるのは正常な運用なので拒否しない（ADR 0003）。
     * 拒否するのは固定時間制 ⇄ フレックスの切り替えだけで、
     * これは適用開始日が月初日か入社日に限られる以上、起きないはずのものである。
     */
    private void requireSingleWorkingTimeSystem(EmployeeId employeeId,
                                                SettlementPeriod period) {
        Set<WorkingTimeSystemType> systems = workRules
                .findEffectiveByPeriod(employeeId, period.period()).values().stream()
                .map(WorkRule::systemType)
                .collect(Collectors.toSet());
        if (systems.size() > 1) {
            throw new WorkingTimeSystemChangedMidMonthException(period.month(), systems);
        }
    }

    /** 社員が見つからない。 */
    public static final class EmployeeNotFoundException extends DomainException {

        private static final long serialVersionUID = 1L;

        EmployeeNotFoundException(EmployeeId employeeId) {
            super("社員が見つかりません: " + employeeId.value());
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:employee-not-found";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.NOT_FOUND;
        }

        @Override
        public String title() {
            return "社員が見つかりません";
        }
    }

    /**
     * その月に 1 日も在籍していない。
     *
     * <p>入社前・退職後の月がこれにあたる。<strong>実装の不備ではないので業務エラーで返す。</strong>
     */
    public static final class NotEmployedInMonthException extends DomainException {

        private static final long serialVersionUID = 1L;

        NotEmployedInMonthException(EmployeeId employeeId, YearMonth month) {
            super("対象月に在籍していません: 社員 %s / 対象月 %s"
                    .formatted(employeeId.value(), month));
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:not-employed-in-month";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.RULE_VIOLATION;
        }

        @Override
        public String title() {
            return "対象月に在籍していません";
        }
    }

    /** 就業規則が適用されていない。人事が適用を登録するまで清算できない。 */
    public static final class WorkRuleNotAssignedException extends DomainException {

        private static final long serialVersionUID = 1L;

        WorkRuleNotAssignedException(EmployeeId employeeId, LocalDate date) {
            super("就業規則が適用されていません: 社員 %s / 基準日 %s"
                    .formatted(employeeId.value(), date));
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:work-rule-not-assigned";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.RULE_VIOLATION;
        }

        @Override
        public String title() {
            return "就業規則が適用されていません";
        }
    }

    /** 締め済みの月は再計算しない（BR-10）。 */
    public static final class MonthAlreadyClosedException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        MonthAlreadyClosedException(YearMonth month) {
            super("締め済みの月は再計算できません: " + month);
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:month-already-closed";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.CONFLICT;
        }

        @Override
        public String title() {
            return "締め済みの月です";
        }
    }

    /**
     * 未計算の勤務日が残っている。
     *
     * <p><strong>どの日かを持つ。</strong>
     * 月次清算は全日の合計で成り立つので、1 日でも欠けると結果が過少になる。
     * 利用者はどの日を直せばよいかを知る必要がある。
     */
    public static final class DailyAttendanceIncompleteException extends DomainException
            implements DetailedDomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final List<LocalDate> incompleteDates;

        DailyAttendanceIncompleteException(List<LocalDate> incompleteDates) {
            super("%s の日次勤怠が確定していません".formatted(
                    incompleteDates.stream().map(LocalDate::toString)
                            .collect(Collectors.joining(", "))));
            this.incompleteDates = List.copyOf(incompleteDates);
        }

        public List<LocalDate> incompleteDates() {
            return incompleteDates;
        }

        /** 画面が機械的に扱えるよう、日付の配列として応答へ載せる。 */
        @Override
        public java.util.Map<String, Object> properties() {
            return java.util.Map.of("incompleteDates",
                    incompleteDates.stream().map(LocalDate::toString).toList());
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:daily-attendance-incomplete";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.CONFLICT;
        }

        @Override
        public String title() {
            return "日次勤怠が未計算の日があります";
        }
    }

    /**
     * 月の途中で労働時間制度が変わっている。
     *
     * <p><strong>就業規則の「改定」とは違う。</strong>
     * 版の改定は月中に起きる正常な運用であり、日ごとに版を引いて計算する。
     * 固定時間制 ⇄ フレックスの切り替えは適用開始日が月初日か入社日に限られるので、
     * 月中で割れているならデータが壊れている。
     */
    public static final class WorkingTimeSystemChangedMidMonthException
            extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        WorkingTimeSystemChangedMidMonthException(YearMonth month,
                                                  Set<WorkingTimeSystemType> systems) {
            super("月の途中で労働時間制度が変わっています: 対象月 %s / 制度 %s"
                    .formatted(month, systems));
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:working-time-system-changed-mid-month";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.RULE_VIOLATION;
        }

        @Override
        public String title() {
            return "月の途中で労働時間制度が変わっています";
        }
    }
}
