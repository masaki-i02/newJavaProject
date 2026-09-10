package jp.co.sample.kintai.workrule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.MonthClosureQuery;
import jp.co.sample.kintai.shared.domain.PayrollExportQuery;
import jp.co.sample.kintai.shared.domain.Requester;
import jp.co.sample.kintai.shared.domain.Role;
import jp.co.sample.kintai.support.TestCalendar;
import jp.co.sample.kintai.support.WorkRules;
import jp.co.sample.kintai.workrule.domain.AnnualScheduledHours;
import jp.co.sample.kintai.workrule.domain.DayType;
import jp.co.sample.kintai.workrule.domain.WorkRule;
import jp.co.sample.kintai.workrule.domain.WorkRuleId;
import jp.co.sample.kintai.workrule.domain.WorkRuleRepository;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeries;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesId;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesRepository;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesUsage;
import jp.co.sample.kintai.workrule.domain.NightWindow;
import jp.co.sample.kintai.workrule.domain.PremiumRates;

/**
 * 年度の所定と、未登録のカレンダーの拒否（UT-PAY-16・BR-18）。
 *
 * <p><strong>「登録されている」と「所定労働日である」は別の事実である。</strong>
 * {@code dayTypeOf} はどちらも {@code WORKDAY} を返すので、
 * この規則の入力は {@code findByPeriod} でしか表現できない。
 *
 * <p>代役が持つのは事実だけである。日数の数え方も未登録の判定も本番が持つ
 * （CLAUDE.md 落とし穴 37・55）。
 */
@DisplayName("年度の所定（BR-18）")
class AnnualScheduledHoursServiceTest {

    private static final int FISCAL_YEAR = 2026;
    private static final DateRange FY = AnnualScheduledHours.periodOf(FISCAL_YEAR);
    private static final WorkRuleSeriesId STANDARD =
            new WorkRuleSeriesId(UUID.randomUUID());
    private static final Requester HR = new Requester(new EmployeeId(UUID.randomUUID()),
            Set.of(Role.EMPLOYEE, Role.HR));

    private final TestCalendar calendar = TestCalendar.allWorkdays();

    /**
     * 2026 年度を全日登録すると、所定労働日は <strong>261 日</strong>になる。
     * 365 日 − 日曜 52 日 − 土曜 52 日。期待値は手で置く（数え直すと恒真になる）。
     */
    @Test
    @DisplayName("UT-PAY-16 全日を登録した年度は所定と月平均が返る")
    void registeredFiscalYearIsAccepted() {
        calendar.registerAll(FY);

        AnnualScheduledHours annual = service(rules(Duration.ofHours(8)))
                .annualScheduledHours(HR, FISCAL_YEAR);

        assertThat(annual.scheduledDays()).isEqualTo(261);
        assertThat(annual.annualTotal()).isEqualTo(Duration.ofMinutes(261 * 480));
        assertThat(annual.monthlyAverage()).isEqualTo(Duration.ofMinutes(10_440));
        assertThat(annual.period()).isEqualTo(FY);
    }

    /**
     * <strong>「法定休日が 1 日も無い」では足りない。</strong>
     * 4 月だけ登録した年度は法定休日を 4 日持つので、その判定は通ってしまう。
     * 所定労働日数は 357 日になり、単価が 27% 低く出る。
     */
    @Test
    @DisplayName("UT-PAY-16 年度の一部しか登録していないと拒否する")
    void partiallyRegisteredFiscalYearIsRejected() {
        calendar.registerAll(new DateRange(LocalDate.of(2026, 4, 1),
                LocalDate.of(2026, 5, 1)));

        assertThatThrownBy(() -> service(rules(Duration.ofHours(8)))
                .annualScheduledHours(HR, FISCAL_YEAR))
                .isInstanceOf(WorkRuleMasterService.CalendarNotRegisteredException.class)
                .hasMessageContaining("未登録の日");
    }

    /**
     * <strong>1 日だけの欠けも拒否する。</strong>
     * 「1 件までは許す」という緩和を入れると、そこから崩れる（落とし穴 24）。
     * 平日で確かめる。休日だけを見る実装が生き残らないようにするためである。
     */
    @Test
    @DisplayName("UT-PAY-16 平日 1 日の登録漏れでも拒否する")
    void singleMissingWeekdayIsRejected() {
        calendar.registerAll(FY);
        TestCalendar missingOne = TestCalendar.allWorkdays().registerAll(FY);
        // 2026-12-15 は火曜。休日ではないので、休日だけを見る実装では捕まらない
        LocalDate missing = LocalDate.of(2026, 12, 15);

        assertThatThrownBy(() -> service(rules(Duration.ofHours(8)),
                withoutDate(missingOne, missing), List.of(usedAllAlong(STANDARD)))
                .annualScheduledHours(HR, FISCAL_YEAR))
                .isInstanceOf(WorkRuleMasterService.CalendarNotRegisteredException.class)
                .hasMessageContaining("2026-12-15");
    }

    /**
     * 年度の端。<strong>3/31 と翌 4/1 は年度の外である。</strong>
     * 範囲を 1 日でも広げると、登録していない日が現れて落ちる。
     */
    @Test
    @DisplayName("UT-PAY-16 年度の外の日は登録されていなくてよい")
    void daysOutsideTheFiscalYearAreNotRequired() {
        calendar.registerAll(FY);

        AnnualScheduledHours annual = service(rules(Duration.ofHours(8)))
                .annualScheduledHours(HR, FISCAL_YEAR);

        assertThat(annual.period().from()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(annual.period().toExclusive()).isEqualTo(LocalDate.of(2027, 4, 1));
    }

    /**
     * <strong>登録に抜けが無くても、中身が誤っていることはある。</strong>
     * 全日を所定労働日として登録した年度は、年間の所定が法定の総枠（労基法 32 条）を超える。
     */
    @Test
    @DisplayName("UT-PAY-16 全日を所定労働日にした年度は法定の総枠を超えて拒否される")
    void allWorkdaysExceedsStatutoryYear() {
        for (LocalDate date = FY.from(); date.isBefore(FY.toExclusive());
                date = date.plusDays(1)) {
            calendar.workday(date);
        }

        assertThatThrownBy(() -> service(rules(Duration.ofHours(8)))
                .annualScheduledHours(HR, FISCAL_YEAR))
                .isInstanceOf(
                        WorkRuleMasterService.CalendarExceedsStatutoryYearException.class)
                .hasMessageContaining("法定の総枠を超えています");
    }

    /**
     * <strong>所定が 1 つに定まらない年度は拒否する。</strong>
     * 要件 3.1 は全社員 1 日 8 時間と定めているが、DB はそれを守っていない。
     * 割れたまま 1 つの分母を返すと、所定の短い社員の単価が法定を下回る。
     */
    @Test
    @DisplayName("UT-PAY-16 年間の所定が就業規則によって違うと拒否する")
    void differentScheduledTimesAreRejected() {
        calendar.registerAll(FY);
        WorkRuleSeriesId shorter = new WorkRuleSeriesId(UUID.randomUUID());

        assertThatThrownBy(() -> service(
                List.of(version(STANDARD, Duration.ofHours(8)),
                        version(shorter, Duration.ofMinutes(465))),
                calendar, List.of(usedAllAlong(STANDARD), usedAllAlong(shorter)))
                .annualScheduledHours(HR, FISCAL_YEAR))
                .isInstanceOf(
                        WorkRuleMasterService.MultipleScheduledWorkingTimesException.class)
                .hasMessageContaining("要件 3.1");
    }

    /**
     * <strong>年度の途中で改定した年度も、日ごとに足せば正しく数えられる。</strong>
     *
     * <p>「所定労働日数 × 1 日の所定」で求めていると、
     * どちらの所定を掛けるかが決まらないので必ず誤る。
     * このテストが無いと、日ごとのループを掛け算に戻す変異が生き残る。
     */
    @Test
    @DisplayName("UT-PAY-18 年度の途中で所定を改定した年度は日ごとに足す")
    void midYearRevisionIsSummedPerDay() {
        calendar.registerAll(FY);
        LocalDate revisedOn = LocalDate.of(2026, 10, 1);

        AnnualScheduledHours annual = service(
                List.of(version(STANDARD, Duration.ofHours(8),
                                new DateRange(LocalDate.of(2020, 4, 1), revisedOn)),
                        version(STANDARD, Duration.ofMinutes(465),
                                DateRange.startingAt(revisedOn))),
                calendar, List.of(usedAllAlong(STANDARD)))
                .annualScheduledHours(HR, FISCAL_YEAR);

        // 4/1〜9/30 の平日 131 日 × 480 分 + 10/1〜3/31 の平日 130 日 × 465 分
        assertThat(annual.scheduledDays()).isEqualTo(261);
        assertThat(annual.annualTotal()).isEqualTo(Duration.ofMinutes(123_330));
        assertThat(annual.monthlyAverage()).isEqualTo(Duration.ofMinutes(123_330 / 12));
    }

    /**
     * <strong>年度の途中で新設した系列があっても出力できる。</strong>
     *
     * <p>10 月からフレックスを導入した会社の新系列は、
     * 4 月〜9 月に版を持たないのが<strong>正常</strong>である。
     * 系列ごとに年度の全所定労働日ぶんの版を要求すると、
     * その年度のどの月も永久に出力できなくなる（落とし穴 131）。
     */
    @Test
    @DisplayName("UT-PAY-19 年度の途中で新設した系列があっても年度の所定は返る")
    void seriesIntroducedMidYearIsAccepted() {
        calendar.registerAll(FY);
        WorkRuleSeriesId introduced = new WorkRuleSeriesId(UUID.randomUUID());
        LocalDate startedOn = LocalDate.of(2026, 10, 1);

        AnnualScheduledHours annual = service(
                List.of(version(STANDARD, Duration.ofHours(8)),
                        version(introduced, Duration.ofHours(8),
                                DateRange.startingAt(startedOn))),
                calendar, List.of(usedAllAlong(STANDARD), usedFrom(introduced, startedOn)))
                .annualScheduledHours(HR, FISCAL_YEAR);

        assertThat(annual.annualTotal()).isEqualTo(Duration.ofMinutes(261 * 480));
    }

    /**
     * <strong>適用されているのに版が無い日は、本当の隙間である。</strong>
     * 0 時間として素通りさせると年間の所定が過少に出て、分母が小さくなり単価が過大になる。
     */
    @Test
    @DisplayName("UT-PAY-20 適用されている系列に版の隙間があると拒否する")
    void versionGapWithinTheAssignmentIsRejected() {
        calendar.registerAll(FY);
        LocalDate gapFrom = LocalDate.of(2026, 10, 1);

        assertThatThrownBy(() -> service(
                List.of(version(STANDARD, Duration.ofHours(8),
                        new DateRange(LocalDate.of(2020, 4, 1), gapFrom))),
                calendar, List.of(usedAllAlong(STANDARD)))
                .annualScheduledHours(HR, FISCAL_YEAR))
                .isInstanceOf(WorkRuleMasterService.WorkRuleVersionMissingException.class);
    }

    /**
     * <strong>所定労働日なのに誰にも規則が適用されていない年度は拒否する。</strong>
     * 導入初年度（1 月から使い始めた会社の年度前半）に必ず起こる。
     * 0 時間として数えると、分母が小さくなり単価が過大になる。
     */
    @Test
    @DisplayName("UT-PAY-21 適用の無い所定労働日がある年度は拒否する")
    void dayWithoutAnyWorkRuleIsRejected() {
        calendar.registerAll(FY);
        LocalDate startedOn = LocalDate.of(2026, 10, 1);

        assertThatThrownBy(() -> service(
                List.of(version(STANDARD, Duration.ofHours(8),
                        DateRange.startingAt(startedOn))),
                calendar, List.of(usedFrom(STANDARD, startedOn)))
                .annualScheduledHours(HR, FISCAL_YEAR))
                .isInstanceOf(WorkRuleMasterService.WorkRuleNotAppliedOnException.class)
                .hasMessageContaining("2026-04-01");
    }

    /** 年度に 1 件も適用が無いと、年度の所定そのものが決まらない。 */
    @Test
    @DisplayName("UT-PAY-22 適用が 1 件も無い年度は拒否する")
    void fiscalYearWithoutAnyAssignmentIsRejected() {
        calendar.registerAll(FY);

        assertThatThrownBy(() -> service(rules(Duration.ofHours(8)), calendar, List.of())
                .annualScheduledHours(HR, FISCAL_YEAR))
                .isInstanceOf(WorkRuleMasterService.WorkRuleNotInUseException.class);
    }

    /** 人事以外は年度の所定を引けない。全社員の賃金の基礎になる値である。 */
    @Test
    @DisplayName("UT-PAY-16 人事でない利用者は引けない")
    void onlyHumanResourcesMayRead() {
        calendar.registerAll(FY);
        Requester employee = new Requester(new EmployeeId(UUID.randomUUID()),
                Set.of(Role.EMPLOYEE));

        assertThatThrownBy(() -> service(rules(Duration.ofHours(8)))
                .annualScheduledHours(employee, FISCAL_YEAR))
                .isInstanceOf(
                        jp.co.sample.kintai.shared.application.AccessDeniedException.class);
    }

    private static TestCalendar withoutDate(TestCalendar source, LocalDate date) {
        TestCalendar copy = TestCalendar.allWorkdays();
        source.findByPeriod(FY).forEach((day, dayType) -> {
            if (!day.equals(date)) {
                copy.save(day, dayType, null);
            }
        });
        return copy;
    }

    private List<WorkRule> rules(Duration daily) {
        return List.of(version(STANDARD, daily));
    }

    private static WorkRule version(WorkRuleSeriesId seriesId, Duration daily) {
        return version(seriesId, daily, DateRange.startingAt(LocalDate.of(2020, 4, 1)));
    }

    private static WorkRule version(WorkRuleSeriesId seriesId, Duration daily,
                                    DateRange validPeriod) {
        return new WorkRule(new WorkRuleId(UUID.randomUUID()), seriesId,
                validPeriod,
                WorkRules.fixed("09:00", fixedEnd(daily), 60),
                Duration.ofHours(8), Duration.ofHours(40),
                NightWindow.STANDARD, PremiumRates.STATUTORY);
    }

    /** 9:00 始業・休憩 60 分で、所定が指定の長さになる終業時刻。 */
    private static String fixedEnd(Duration daily) {
        return java.time.LocalTime.of(9, 0).plus(daily).plusMinutes(60).toString();
    }

    private WorkRuleMasterService service(List<WorkRule> versions) {
        return service(versions, calendar, List.of(usedAllAlong(STANDARD)));
    }

    /** 期限の無い適用。導入時から使い続けている系列。 */
    private static WorkRuleSeriesUsage usedAllAlong(WorkRuleSeriesId seriesId) {
        return new WorkRuleSeriesUsage(seriesId,
                DateRange.startingAt(LocalDate.of(2020, 4, 1)));
    }

    /** その日から使い始めた適用。 */
    private static WorkRuleSeriesUsage usedFrom(WorkRuleSeriesId seriesId, LocalDate from) {
        return new WorkRuleSeriesUsage(seriesId, DateRange.startingAt(from));
    }

    /** 代役は事実だけを答える。判定も数え方も本番が持つ。 */
    private WorkRuleMasterService service(List<WorkRule> versions, TestCalendar calendar,
                                          List<WorkRuleSeriesUsage> inUse) {
        WorkRuleSeriesRepository series = new WorkRuleSeriesRepository() {
            @Override
            public java.util.Optional<WorkRuleSeries> findById(WorkRuleSeriesId id) {
                return java.util.Optional.empty();
            }

            @Override
            public List<WorkRuleSeries> findAll() {
                throw new UnsupportedOperationException("全系列は舐めない");
            }

            @Override
            public boolean bumpVersion(WorkRuleSeriesId id, long expectedVersion) {
                throw new UnsupportedOperationException("分母の計算は改定しない");
            }

            @Override
            public void save(WorkRuleSeries series) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void assign(EmployeeId employeeId, WorkRuleSeriesId seriesId,
                               LocalDate validFrom) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<jp.co.sample.kintai.workrule.domain.WorkRuleAssignment>
                    findAssignments(EmployeeId employeeId) {
                return List.of();
            }

            @Override
            public List<EmployeeId> findEmployeesWithRuleOn(LocalDate date) {
                return List.of();
            }

            @Override
            public List<WorkRuleSeriesUsage> findUsagesIn(DateRange period) {
                return inUse;
            }
        };
        WorkRuleRepository workRules = new WorkRuleRepository() {
            @Override
            public java.util.Optional<WorkRule> findEffective(EmployeeId employeeId,
                                                              LocalDate date) {
                return java.util.Optional.empty();
            }

            @Override
            public java.util.Map<LocalDate, WorkRule> findEffectiveByPeriod(
                    EmployeeId employeeId, DateRange period) {
                return java.util.Map.of();
            }

            @Override
            public java.util.Optional<WorkRule> findById(WorkRuleId id) {
                return java.util.Optional.empty();
            }

            @Override
            public void save(WorkRule rule) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void revise(List<WorkRule> closed, WorkRule added) {
                throw new UnsupportedOperationException("分母の計算は改定しない");
            }

            @Override
            public List<WorkRule> findVersionsOf(WorkRuleSeriesId seriesId) {
                return versions.stream()
                        .filter(version -> version.seriesId().equals(seriesId)).toList();
            }
        };
        MonthClosureQuery closure = new MonthClosureQuery() {
            @Override
            public boolean isClosed(EmployeeId employeeId, YearMonth month) {
                return false;
            }

            @Override
            public boolean acceptsTimeClock(EmployeeId employeeId, YearMonth month) {
                return true;
            }

            @Override
            public boolean acceptsCorrectionRequest(EmployeeId employeeId, YearMonth month) {
                return true;
            }

            @Override
            public boolean isClosedForAnyone(YearMonth month) {
                return false;
            }
        };
        PayrollExportQuery exports = fiscalYear -> List.of();
        return new WorkRuleMasterService(calendar, series, workRules, closure, exports);
    }
}
