package jp.co.sample.kintai.attendance.application;

import java.io.Serial;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sample.kintai.attendance.domain.DailyAttendance;
import jp.co.sample.kintai.attendance.domain.DailyAttendanceRepository;
import jp.co.sample.kintai.attendance.domain.TimeClockEventRepository;
import jp.co.sample.kintai.attendance.domain.monthly.MonthlyDayCounts;
import jp.co.sample.kintai.attendance.domain.monthly.AgreementUsage;
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
import jp.co.sample.kintai.workrule.domain.CompanyCalendarRepository;
import jp.co.sample.kintai.workrule.domain.RegisteredCalendar;
import jp.co.sample.kintai.workrule.domain.DayType;
import jp.co.sample.kintai.workrule.domain.SettlementPeriod;
import jp.co.sample.kintai.workrule.domain.WorkRule;
import jp.co.sample.kintai.workrule.domain.WorkRuleId;
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
    private final CompanyCalendarRepository calendar;
    private final MonthClosureQuery monthClosure;
    private final EmployeeVisibility visibility;
    private final PaidLeaveDays paidLeaveDays;

    public MonthlySettlementService(DailyAttendanceRepository dailyAttendances,
                                    TimeClockEventRepository timeClocks,
                                    MonthlySettlementRepository settlements,
                                    WorkRuleRepository workRules,
                                    EmployeeRepository employees,
                                    CompanyCalendarRepository calendar,
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
     * <p>適用する就業規則は<strong>清算期間を通じて 1 つ</strong>である。
     * 割れていたら計算せずに拒む（{@link #singleWorkRuleOf}）。
     */
    @Transactional
    public MonthlySettlement settle(EmployeeId employeeId, YearMonth month) {
        MonthlySettlement settlement = settleOnly(employeeId, month);
        cascadeToLaterMonths(employeeId, month);
        return settlement;
    }

    /** その月だけを清算する。<strong>後続月へ連鎖しない。</strong> */
    private MonthlySettlement settleOnly(EmployeeId employeeId, YearMonth month) {
        SettlementPeriod period = periodOf(employeeId, month);
        MonthlySettlement settlement = calculate(employeeId, period);
        settlements.save(settlement);
        return settlement;
    }

    /**
     * 同じ年度の後続月を計算し直す。
     *
     * <p><strong>年度累計は行に焼き付けてある</strong>（{@code annualUsedBefore}）。
     * 過去月が動いたのに後続月をそのままにすると、
     * 36 条 4 項の年 360 時間の判定が<strong>過少なまま残り続ける</strong>。
     * 4 月が訂正で 50 時間から 80 時間に増えても、5 月以降は 50 時間として
     * 数え続けるので、超過を取りこぼす。
     *
     * <p><strong>行が無い月は作らない。</strong> 行の有無は「その月に打刻があるか」を
     * 表しており、連鎖のついでに作ると、働いていない月の行ができる。
     *
     * <p><strong>締め済みの月は動かさない</strong>（BR-10）。確定した値は動かせないので、
     * 締めたあとに過去を直しても、その月の年度累計は当時のまま残る。
     * これは締めの不可逆性から来る帰結であり、連鎖で覆してはならない。
     */
    private void cascadeToLaterMonths(EmployeeId employeeId, YearMonth from) {
        YearMonth lastOfFiscalYear = YearMonth.from(
                AgreementUsage.fiscalYearStartOf(from)).plusMonths(11);
        for (YearMonth month = from.plusMonths(1);
                !month.isAfter(lastOfFiscalYear); month = month.plusMonths(1)) {
            if (settlements.find(employeeId, month).isEmpty()) {
                continue;
            }
            if (monthClosure.isClosed(employeeId, month)) {
                continue;
            }
            settleOnly(employeeId, month);
        }
    }

    /**
     * 打刻の登録を契機に計算し直す。<strong>失敗しても例外にしない。</strong>
     *
     * <p><strong>なぜ要るか。</strong> {@code monthly_settlements} の行は提出して初めて
     * 作られていた。つまり<strong>進行中の月には行が無く</strong>、
     * 36 協定の超過者一覧（BR-12）は当月について常に空を返し、
     * 本人・上長の月次照会も 404 だった。
     * 警告が出るのは翌月に提出したあと、すなわち
     * <strong>45 時間・100 時間を既に超えたあと</strong>である。
     * 「上限監視」と名のつくものが、超えるまで何も言わない状態だった。
     *
     * <p><strong>打刻を止めない。</strong> 就業規則の未設定・月中の制度変更などで
     * 計算が成立しない月はある。そこで例外を投げると、働いた事実そのものが
     * 記録されない（落とし穴 19）。打刻は成功させ、計算だけを行わない。
     *
     * @return 計算できたときだけ結果。できなければ空
     */
    @Transactional
    public Optional<MonthlySettlement> refresh(EmployeeId employeeId, YearMonth month) {
        try {
            return Optional.of(settle(employeeId, month));
        } catch (DomainException e) {
            return Optional.empty();
        }
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
        WorkRule workRule = singleWorkRuleOf(employeeId, period);

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
                .filter(DailyAttendance::hasWork)
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
        return dayCountsIn(employeeId, periodOf(employeeId, month));
    }

    /**
     * 清算期間を<strong>外から渡す</strong>版。
     *
     * <p>締め済みの月の CSV を作り直すときに使う。
     * 締めたあとに退職日が登録されると {@link #periodOf} の返す清算期間が縮むので、
     * 引き直すと<strong>同じ出力の記録から出る CSV が変わる</strong>（落とし穴 122）。
     * 保存済みの月次清算が持つ清算期間を渡して固定する。
     */
    @Transactional(readOnly = true)
    public MonthlyDayCounts dayCountsIn(EmployeeId employeeId, SettlementPeriod period) {
        // ★ 暦月そのもの（清算期間ではない）。日額の分母は労基法 24 条により暦月で数える
        DateRange monthRange = DateRange.ofMonth(period.month());
        // ★ 日ごとに findById を投げると 1 社員あたり 31 往復する。まとめて 1 回で読む
        CompanyCalendar registered = new RegisteredCalendar(calendar.findByPeriod(monthRange));

        int monthlyScheduledDays = registered.workdayCountIn(monthRange);
        int scheduledDays = registered.workdayCountIn(period.period());
        Set<LocalDate> leaveDates = paidLeaveDays.approvedOn(employeeId, period.period())
                .stream()
                .filter(date -> registered.dayTypeOf(date) == DayType.WORKDAY)
                .collect(Collectors.toSet());

        List<DailyAttendance> worked = dailyAttendances
                .findByPeriod(employeeId, period.period()).stream()
                .filter(DailyAttendance::hasWork)
                .toList();
        int attendedDays = worked.size();
        Set<LocalDate> workedDates = worked.stream()
                .map(DailyAttendance::workDate)
                .collect(Collectors.toSet());

        // ★ 引き算で導かない。年休の日に出勤した月（落とし穴 97）では
        //   同じ日が年休と実労働の両方に数えられ、引くと欠勤が 1 日少なく出る。
        //   定義（所定労働日のうち、年休でもなく実労働が 1 分も無い日）をそのまま数える
        int absentDays = 0;
        for (LocalDate date = period.period().from();
                date.isBefore(period.period().toExclusive()); date = date.plusDays(1)) {
            if (registered.dayTypeOf(date) == DayType.WORKDAY
                    && !leaveDates.contains(date) && !workedDates.contains(date)) {
                absentDays++;
            }
        }

        return new MonthlyDayCounts(monthlyScheduledDays, scheduledDays, attendedDays,
                leaveDates.size(), absentDays);
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
        // ★ 提出は月次清算を計算し直すので、それが落ちる条件も「提出できない」である。
        //   判定を写さず、計算が実際に呼ぶ解決をそのまま通す（落とし穴 67）。
        //   捕まえるのは singleWorkRuleOf が投げうる 3 つだけで、
        //   他の例外は「提出できない」ではなく本当の失敗なので伝播させる
        try {
            singleWorkRuleOf(employeeId, period);
            return true;
        } catch (WorkRuleNotAssignedException | WorkingTimeSystemChangedMidMonthException
                | WorkRuleRevisedMidMonthException expected) {
            return false;
        }
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

    /**
     * 36 協定の超過者一覧（04 API 設計書 4）。
     *
     * <p><strong>判定はドメインに任せる。</strong>
     * 「超えているか」は年度の累計と休日労働の扱いを含む業務ルールであり、
     * {@code AgreementUsage} が持っている。SQL の {@code WHERE} に写すと
     * 同じ規則が 2 か所に分かれる（落とし穴 69）。
     *
     * <p><strong>限度時間に休日労働を数えない。</strong>
     * 36 条 3 項・4 項の対象は時間外労働だけで、休日労働を含めるのは
     * 6 項 2 号・3 号という<strong>別の規制</strong>である（落とし穴 52）。
     *
     * <p>社員番号・氏名・部署は返さない。{@code employee} が所有する概念である。
     *
     * <p><strong>ロールで一律に拒まない。閲覧範囲で絞る</strong>
     * （決定表「一覧を返す API のロール」）。
     * 人事だけに開いていたが、<strong>36 協定の超過を是正できるのは
     * 業務の配分を変えられる上長だけ</strong>である。人事は数字を見られても
     * 仕事を配れない。要件 4.1 は承認者に「配下部署の社員の勤怠」を
     * 既に認めており、1 人ずつなら {@link #find} で同じ値が読めるので、
     * <strong>ここを開いても見える範囲は 1 ミリも広がらない。</strong>
     * 面で見る経路が無いことだけが問題だった。
     *
     * <p>基準日は<strong>対象月の末日</strong>にそろえる（{@code find} と同じ）。
     * 揃えないと、同じ社員が一覧に出るのに詳細を開けない月ができる。
     */
    @Transactional(readOnly = true)
    public List<AgreementAlert> agreementAlerts(Requester requester, YearMonth month,
                                                AlertType type) {
        return settlements.findByMonth(month).stream()
                .filter(settlement -> visibility.canView(requester,
                        settlement.employeeId(), month.atEndOfMonth()))
                .map(settlement -> new AgreementAlert(settlement.employeeId(),
                        settlement.agreementUsage()))
                .filter(alert -> type.matches(alert.usage()))
                .toList();
    }

    /**
     * 一覧に載せる条件。
     *
     * <p><strong>月と年を分けて絞れるようにする。</strong>
     * 月の限度を超えた社員と、年の限度に迫っている社員では、
     * 人事が取る手段（当月の是正 / 特別条項の検討）が違う。
     */
    public enum AlertType {
        /** 月の限度時間（原則 45 時間）を超えた。 */
        MONTHLY {
            @Override
            boolean matches(AgreementUsage usage) {
                return usage.exceedsMonthly();
            }
        },
        /** 年の限度時間（原則 360 時間）を超えた。 */
        ANNUAL {
            @Override
            boolean matches(AgreementUsage usage) {
                return usage.exceedsAnnual();
            }
        },
        /**
         * 単月 100 時間未満（36 条 6 項 2 号）に触れた。
         *
         * <p><strong>限度時間とは別の規制である</strong>（落とし穴 52）。
         * 対象は時間外労働 <strong>+ 法定休日労働</strong>で、
         * 特別条項でも超えられない<strong>絶対的な上限</strong>である。
         */
        COMBINED_SINGLE_MONTH {
            @Override
            boolean matches(AgreementUsage usage) {
                return usage.exceedsCombinedSingleMonth();
            }
        },
        /**
         * いずれかに触れた。既定。
         *
         * <p><strong>3 つを並べ直さない。</strong> `AgreementUsage.hasWarning()` を呼ぶ。
         * 写すと、規制を足したときに片方だけが古くなる（落とし穴 67）。
         * 実際、6 項 2 号はドメインにあるのにここに無く、
         * <strong>時間外 40 時間 + 法定休日 60 時間の社員が一覧に 1 行も出なかった。</strong>
         */
        ALL {
            @Override
            boolean matches(AgreementUsage usage) {
                return usage.hasWarning();
            }
        };

        abstract boolean matches(AgreementUsage usage);
    }

    /** 超過している社員 1 人ぶん。 */
    public record AgreementAlert(EmployeeId employeeId, AgreementUsage usage) {
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
     * 清算期間を通じて適用されている、ただ 1 つの版を返す。
     *
     * <p><strong>末日の版だけを引かない。</strong>
     * 月次清算は所定総労働時間・不足時間・法定総枠のどれもを 1 つの版から求めるので、
     * 期間の中で版が割れていると<strong>片方の版の値だけで 1 か月を計算する。</strong>
     * 賃金がずれるうえ、どちらの版で計算したのかが結果から読み取れない。
     *
     * <p><strong>端の 2 点ではなく、期間の全日を見る</strong>（落とし穴 136）。
     * 適用が月中で途切れて再開した月は、両端だけを見ると同じ版で一致する。
     *
     * <p><strong>版が割れていること自体は拒まない。</strong>
     * 深夜帯や割増率だけを月中から変える改定は正常な運用であり（ADR 0003・IT-SCN-09）、
     * 日次計算は日ごとに版を引いて正しく扱っている。
     * 月次に効くのは所定労働時間・法定労働時間・労働時間制度だけなので、
     * <strong>そこが割れている場合に限って</strong>拒む。
     *
     * <p>その組み合わせは {@code WorkRuleMasterService} が改定の時点で拒んでいる。
     * ここは<strong>経路の外から作られた状態に対する最後の防波堤</strong>である
     * （落とし穴 58）。
     */
    private WorkRule singleWorkRuleOf(EmployeeId employeeId, SettlementPeriod period) {
        Map<LocalDate, WorkRule> byDate =
                workRules.findEffectiveByPeriod(employeeId, period.period());
        // 規則の引けない日はキーごと現れない。1 日でも欠けていれば計算できない
        for (LocalDate date = period.period().from();
                date.isBefore(period.period().toExclusive()); date = date.plusDays(1)) {
            if (!byDate.containsKey(date)) {
                throw new WorkRuleNotAssignedException(employeeId, date);
            }
        }
        Set<WorkingTimeSystemType> systems = byDate.values().stream()
                .map(WorkRule::systemType).collect(Collectors.toSet());
        if (systems.size() > 1) {
            // 制度の切り替えは別のエラーにする。利用者への案内がまったく違う
            throw new WorkingTimeSystemChangedMidMonthException(period.month(), systems);
        }
        // 月中の改定そのものは正常な運用である（ADR 0003）。
        // 効くのは所定・法定労働時間・制度が割れているかどうかだけ
        WorkRule first = byDate.get(period.period().from());
        List<WorkRuleId> differing = byDate.values().stream()
                .filter(rule -> !first.hasSameMonthlyBasisAs(rule))
                .map(WorkRule::id).distinct().toList();
        if (!differing.isEmpty()) {
            throw new WorkRuleRevisedMidMonthException(period.month(), differing);
        }
        return first;
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
     * <p><strong>版の改定（{@link WorkRuleRevisedMidMonthException}）とは別に扱う。</strong>
     * 制度が変わると所定の数え方そのものが変わるので、
     * 人事が直すべき対象も案内も違う。
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

    /**
     * 月の途中で、月次清算に効く値が変わっている。
     *
     * <p>月次清算は<strong>1 つの版で 1 か月を計算する。</strong>
     * 所定総労働時間も不足時間も法定総枠も、清算期間を通じた 1 つの所定から求まる。
     * 所定の割れた月をどちらかの版で計算すると、
     * <strong>もう片方の期間の所定が結果のどこにも現れない。</strong>
     *
     * <p>深夜帯や割増率だけを変えた改定では起きない。
     * 所定を変える改定は月初日からに限っているので、通常の運用では起きない。
     */
    public static final class WorkRuleRevisedMidMonthException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        WorkRuleRevisedMidMonthException(YearMonth month, List<WorkRuleId> versions) {
            super("月の途中で所定労働時間が変わっています: 対象月 %s / 版 %s"
                    .formatted(month, versions.stream()
                            .map(id -> id.value().toString()).toList()));
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:work-rule-revised-mid-month";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.RULE_VIOLATION;
        }

        @Override
        public String title() {
            return "月の途中で所定労働時間が変わっています";
        }
    }
}
