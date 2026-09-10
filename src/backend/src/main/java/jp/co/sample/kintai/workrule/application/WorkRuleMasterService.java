package jp.co.sample.kintai.workrule.application;

import java.io.Serial;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sample.kintai.shared.application.AccessDeniedException;
import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.MonthClosureQuery;
import jp.co.sample.kintai.shared.domain.PayrollExportQuery;
import jp.co.sample.kintai.shared.domain.PayrollExportQuery.UsedDivisor;
import jp.co.sample.kintai.shared.domain.Requester;
import jp.co.sample.kintai.shared.domain.Role;
import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.workrule.domain.AnnualScheduledHours;
import jp.co.sample.kintai.workrule.domain.CompanyCalendar;
import jp.co.sample.kintai.workrule.domain.CompanyCalendarRepository;
import jp.co.sample.kintai.workrule.domain.DayType;
import jp.co.sample.kintai.workrule.domain.RegisteredCalendar;
import jp.co.sample.kintai.workrule.domain.FlextimeSystem;
import jp.co.sample.kintai.workrule.domain.NightWindow;
import jp.co.sample.kintai.workrule.domain.PremiumRates;
import jp.co.sample.kintai.workrule.domain.ScheduleCapacityWarning;
import jp.co.sample.kintai.workrule.domain.SettlementPeriod;
import jp.co.sample.kintai.workrule.domain.WorkRuleId;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeries;
import jp.co.sample.kintai.workrule.domain.WorkingTimeSystem;
import jp.co.sample.kintai.workrule.domain.WorkRule;
import jp.co.sample.kintai.workrule.domain.WorkRuleRepository;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesId;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesUsage;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesRepository;

/**
 * カレンダーと就業規則の適用を変更する（人事）。
 *
 * <p><strong>締め済みの月に影響する変更を拒む。</strong>
 * 暦日区分が変わると休日割増の計算が変わり、就業規則が変わると所定が変わる。
 * どちらも<strong>確定済みの勤怠と矛盾する。</strong>
 * 締めた月を戻す手段は用意していないので、矛盾したまま残る。
 *
 * <p>締め状態は {@code shared.domain} の {@link MonthClosureQuery} 越しに問う。
 * {@code approval} の型を直接見ると、依存図に無い
 * <strong>{@code workrule → approval} の辺</strong>が生まれる（ADR 0004）。
 */
@Service
public class WorkRuleMasterService {

    /** 一括設定で一度に登録できる日数の上限。年度の登録が目的なので 3 年で足りる。 */
    private static final int MAX_BULK_DAYS = 1096;

    private final CompanyCalendarRepository calendar;
    private final WorkRuleSeriesRepository series;
    private final WorkRuleRepository workRules;
    private final MonthClosureQuery monthClosure;
    private final PayrollExportQuery payrollExports;

    public WorkRuleMasterService(CompanyCalendarRepository calendar,
                                 WorkRuleSeriesRepository series,
                                 WorkRuleRepository workRules,
                                 MonthClosureQuery monthClosure,
                                 PayrollExportQuery payrollExports) {
        this.calendar = calendar;
        this.series = series;
        this.workRules = workRules;
        this.monthClosure = monthClosure;
        this.payrollExports = payrollExports;
    }

    /**
     * 暦日区分を設定する。
     *
     * <p><strong>判定は「誰か 1 人でも締めたか」で行う。</strong>
     * カレンダーは全社で共有する 1 つの表なので、
     * 特定の社員が未締めでも、他の社員が締めていれば変更してはいけない。
     */
    @Transactional
    public void setDayType(Requester requester, LocalDate date, DayType dayType,
                            String name) {
        requireHumanResources(requester);
        YearMonth month = YearMonth.from(date);
        if (monthClosure.isClosedForAnyone(month)) {
            throw MonthClosureQuery.MonthAlreadyClosedException.affecting(month, "会社カレンダー");
        }
        calendar.save(date, dayType, name);
        requireDivisorUnchanged(date);
    }

    /**
     * 期間の暦日区分をまとめて設定する（API設計書 3.2）。
     *
     * <p><strong>年度初めにまとめて登録するための操作である。</strong>
     * 1 日ずつの API しか無いと、年度ぶんを登録するのに 365 回叩くことになり、
     * 割増賃金の基礎額の分母（BR-18）が要求する
     * 「年度の全日が登録されていること」を運用で満たせない。
     *
     * <p>曜日の規則を先に当て、そのあと個別の日で上書きする。
     * <strong>祝日は曜日で決まらない</strong>ので、この 2 段構えが要る。
     *
     * <p>締め済みの月を含む期間は拒否する。1 日ずつの API と同じ判断である
     * （確定済みの勤怠と矛盾する）。
     *
     * <p><strong>利用者が送る値の検証をここで行う。</strong>
     * {@code DateRange} の compact constructor や {@code Collectors.toMap} に任せると、
     * 期間が逆でも曜日が重複していても {@code IllegalArgumentException} /
     * {@code IllegalStateException} になり、<strong>理由の載らない 500</strong> が返る。
     * compact constructor は最後の防波堤であって、業務エラーの窓口ではない（落とし穴 105）。
     *
     * @param rules     曜日ごとの既定。指定の無い曜日は所定労働日
     * @param overrides 個別の日。曜日の規則より優先する
     */
    @Transactional
    public CalendarRegistration setDayTypesInBulk(
            Requester requester, LocalDate from, LocalDate toExclusive,
            List<CalendarDayOfWeekRule> rules, List<CalendarOverride> overrides) {
        requireHumanResources(requester);
        DateRange period = requireValidPeriod(from, toExclusive);
        Map<DayOfWeek, DayTypeAndName> byDayOfWeek = byDayOfWeek(rules);
        Map<LocalDate, DayTypeAndName> byDate = overrides(overrides, period);
        requireNoClosedMonth(period);

        Map<DayType, Integer> counts = new EnumMap<>(DayType.class);
        for (LocalDate date = period.from(); date.isBefore(period.toExclusive());
                date = date.plusDays(1)) {
            DayTypeAndName decided = byDate.getOrDefault(date,
                    byDayOfWeek.getOrDefault(date.getDayOfWeek(),
                            new DayTypeAndName(DayType.WORKDAY, null)));
            calendar.save(date, decided.dayType(), decided.name());
            counts.merge(decided.dayType(), 1, Integer::sum);
        }

        requireDivisorUnchanged(period);

        RegisteredCalendar registered = new RegisteredCalendar(calendar.findByPeriod(period));
        return new CalendarRegistration(Map.copyOf(counts),
                weeksWithoutLegalHoliday(period, registered));
    }

    /**
     * 出力に使った分母（労基則 19 条 1 項 4 号）を動かす変更を拒む。
     *
     * <p><strong>締め済みの月の判定だけでは足りない。</strong>
     * 分母は<strong>年度全体</strong>の所定労働日数から決まるので、
     * 4 月分の給与を払ったあとに 12 月（未締め）の休日を増やすと、
     * <strong>既に払った割増賃金の単価が事後的に足りなくなる</strong>（労基法 37 条は下限）。
     *
     * <p><strong>「出力したか」ではなく「分母が動くか」で判定する。</strong>
     * 出力の有無だけで拒むと、
     * <ul>
     *   <li>法定休日と所定休日の付け替えや名称の訂正のように<strong>分母を動かさない変更</strong>
     *       まで止まり、年度いっぱい暦日区分の誤りを直せなくなる（BR-07 の 35% 判定が誤ったまま固定される）</li>
     *   <li>逆に、<strong>就業規則の適用や改定</strong>（分母のもう一方の入力）は素通りする</li>
     * </ul>
     * のどちらも起きる。書き込んだあとに数え直して、記録した値と突き合わせる。
     *
     * <p><strong>変更したあとに呼ぶ。</strong> 例外が飛べばトランザクションごと巻き戻る。
     * 前もって「変更したらどうなるか」を組み立てると、
     * 数え方の実装が 2 か所になる（落とし穴 67）。
     *
     * @param changed 変更が触れた期間。ここに重なる年度をすべて調べる
     */
    private void requireDivisorUnchanged(DateRange changed) {
        int last = AnnualScheduledHours.fiscalYearOf(
                YearMonth.from(changed.toExclusive().minusDays(1)));
        requireDivisorUnchangedFrom(changed.from(),
                used -> used.fiscalYear() <= last);
    }

    /** 1 日の変更。 */
    private void requireDivisorUnchanged(LocalDate date) {
        requireDivisorUnchanged(new DateRange(date, date.plusDays(1)));
    }

    /**
     * 終わりの無い変更（就業規則の適用）。
     *
     * <p><strong>年度の上限を呼ぶ側で決めない。</strong>
     * 適用は期限を持たないので、どこまで効くかは
     * 「出力のある年度がどこまであるか」でしか決まらない。
     */
    private void requireDivisorUnchangedFrom(LocalDate from) {
        requireDivisorUnchangedFrom(from, used -> true);
    }

    private void requireDivisorUnchangedFrom(LocalDate from,
                                             Predicate<UsedDivisor> within) {
        int first = AnnualScheduledHours.fiscalYearOf(YearMonth.from(from));
        for (UsedDivisor used : payrollExports.usedDivisorsFrom(first)) {
            if (!within.test(used)) {
                continue;
            }
            DateRange period = AnnualScheduledHours.periodOf(used.fiscalYear());
            long now;
            try {
                now = annualScheduledTimeOf(used.fiscalYear(), period,
                        new RegisteredCalendar(calendar.findByPeriod(period))).toMinutes();
            } catch (DomainException e) {
                // 分母そのものが求められなくなった。動いたかどうか以前の問題である
                throw new FiscalYearUsedByPayrollException(used.fiscalYear());
            }
            if (now != used.annualScheduledMinutes()) {
                throw new FiscalYearUsedByPayrollException(used.fiscalYear());
            }
        }
    }

    /** 締め済みの月を 1 つでも含む期間は拒否する。 */
    private void requireNoClosedMonth(DateRange period) {
        YearMonth month = YearMonth.from(period.from());
        YearMonth last = YearMonth.from(period.toExclusive().minusDays(1));
        while (!month.isAfter(last)) {
            if (monthClosure.isClosedForAnyone(month)) {
                throw MonthClosureQuery.MonthAlreadyClosedException.affecting(month, "会社カレンダー");
            }
            month = month.plusMonths(1);
        }
    }

    /**
     * 法定休日の無い連続 7 日間（労基法 35 条）。
     *
     * <p><strong>DB では守れない</strong>ので、登録の時点で警告として返す
     * （DB設計書 3.5）。手続きは止めない。
     */
    private List<DateRange> weeksWithoutLegalHoliday(DateRange period,
                                                     CompanyCalendar registered) {
        List<DateRange> found = new ArrayList<>();
        LocalDate last = period.toExclusive().minusDays(7);
        for (LocalDate start = period.from(); !start.isAfter(last);
                start = start.plusDays(1)) {
            boolean hasLegalHoliday = false;
            for (int offset = 0; offset < 7; offset++) {
                if (registered.dayTypeOf(start.plusDays(offset)) == DayType.LEGAL_HOLIDAY) {
                    hasLegalHoliday = true;
                    break;
                }
            }
            if (!hasLegalHoliday) {
                found.add(new DateRange(start, start.plusDays(7)));
            }
        }
        return List.copyOf(found);
    }

    /** 一括設定の結果。 */
    /**
     * 期間の妥当性。
     *
     * <p>上限を置く。置かないと 1000 年ぶんの登録を要求でき、
     * 1 トランザクションで 36 万行を書くことになる。
     * 年度の登録が目的なので 3 年あれば足りる。
     */
    private static DateRange requireValidPeriod(LocalDate from, LocalDate toExclusive) {
        if (from == null || toExclusive == null) {
            throw new InvalidCalendarRequestException("期間の指定がありません");
        }
        if (!from.isBefore(toExclusive)) {
            throw new InvalidCalendarRequestException(
                    "期間の終わりは始まりより後でなければなりません: %s 〜 %s"
                            .formatted(from, toExclusive));
        }
        long days = ChronoUnit.DAYS.between(from, toExclusive);
        if (days > MAX_BULK_DAYS) {
            throw new InvalidCalendarRequestException(
                    "一度に登録できるのは %d 日までです: %d 日".formatted(MAX_BULK_DAYS, days));
        }
        return new DateRange(from, toExclusive);
    }

    private static Map<DayOfWeek, DayTypeAndName> byDayOfWeek(
            List<CalendarDayOfWeekRule> rules) {
        Map<DayOfWeek, DayTypeAndName> found = new EnumMap<>(DayOfWeek.class);
        for (CalendarDayOfWeekRule rule : rules) {
            // ★ 後勝ちにしない。どちらを意図したのか決められないので、送り直させる
            if (found.put(rule.dayOfWeek(),
                    new DayTypeAndName(rule.dayType(), rule.name())) != null) {
                throw new InvalidCalendarRequestException(
                        "同じ曜日の規則が 2 つあります: " + rule.dayOfWeek());
            }
        }
        return found;
    }

    private static Map<LocalDate, DayTypeAndName> overrides(List<CalendarOverride> overrides,
                                                            DateRange period) {
        Map<LocalDate, DayTypeAndName> found = new LinkedHashMap<>();
        for (CalendarOverride override : overrides) {
            if (!period.contains(override.date())) {
                // ★ 黙って捨てない。登録したつもりの祝日が入っていない状態を作る
                throw new InvalidCalendarRequestException(
                        "個別指定が期間の外にあります: %s（期間 %s）"
                                .formatted(override.date(), period));
            }
            if (found.put(override.date(),
                    new DayTypeAndName(override.dayType(), override.name())) != null) {
                throw new InvalidCalendarRequestException(
                        "同じ日の個別指定が 2 つあります: " + override.date());
            }
        }
        return found;
    }

    /** 曜日ごとの既定（一括設定の入力）。 */
    public record CalendarDayOfWeekRule(DayOfWeek dayOfWeek, DayType dayType, String name) {
    }

    /** 個別の日の指定（一括設定の入力）。 */
    public record CalendarOverride(LocalDate date, DayType dayType, String name) {
    }

    /**
     * 一括設定の依頼そのものの不備。
     *
     * <p><strong>422 で返す。</strong> 人事が何を直せばよいかを本文で伝える。
     */
    public static final class InvalidCalendarRequestException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        InvalidCalendarRequestException(String message) {
            super(message);
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:invalid-calendar-request";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.RULE_VIOLATION;
        }

        @Override
        public String title() {
            return "カレンダーの一括設定の指定が不正です";
        }
    }

    public record CalendarRegistration(Map<DayType, Integer> byDayType,
                                       List<DateRange> weeksWithoutLegalHoliday) {

        public int registeredCount() {
            return byDayType.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    /** 暦日区分と表示名。 */
    public record DayTypeAndName(DayType dayType, String name) {
    }

    /**
     * 年度の所定と、割増賃金の基礎額に使う 1 か月平均所定労働時間数（BR-18）。
     *
     * <p>労基則 19 条 1 項 4 号は、月給の場合の基礎額を
     * 「1 年間における 1 か月平均所定労働時間数」で除して求めると定める。
     * <strong>この値は分母なので、大きく出ると単価が法定を下回る。</strong>
     * 静かに返してよい値ではないので、確かめられないときは拒否する。
     *
     * <p><strong>「日数 × 1 日の所定」では求めない。</strong>
     * 就業規則は年度の途中で改定できるので、その式は
     * 「1 日の所定が年度を通じて一定」を暗黙に仮定している。
     * 日ごとにその日の版を引いて足す。
     *
     * <p>検査の順序に意味がある。所定が 1 つに定まらないなら、
     * カレンダーの話に進んでも意味が無い。
     */
    @Transactional(readOnly = true)
    public AnnualScheduledHours annualScheduledHours(Requester requester, int fiscalYear) {
        requireHumanResources(requester);
        DateRange period = AnnualScheduledHours.periodOf(fiscalYear);

        RegisteredCalendar registered = new RegisteredCalendar(calendar.findByPeriod(period));
        Duration annualTotal = annualScheduledTimeOf(fiscalYear, period, registered);

        requireCalendarRegistered(fiscalYear, period, registered);
        requireWithinStatutoryYear(fiscalYear, period, annualTotal);
        // ★ 全日を休日として登録した年度。分母が 0 になり、給与側がゼロ除算する。
        //   業務エラーとして返す。素通りさせると集約の compact constructor で 500 になる
        if (annualTotal.isZero()) {
            throw new NoScheduledWorkdayException(fiscalYear);
        }

        return AnnualScheduledHours.of(fiscalYear, registered.workdayCountIn(period),
                annualTotal);
    }

    /**
     * 年度に適用されている就業規則から、年間の所定労働時間を求める。
     *
     * <p>系列ごとに<strong>日ごとの版</strong>を引いて足し、
     * 系列のあいだで値が割れていたら拒否する。
     * 要件 3.1 は全社員 1 日 8 時間と定めているが、
     * <strong>DB はそれを守っていない</strong>（所定は法定の 8 時間を上限とする 1 分単位の値）。
     * 割れたまま 1 つの値を返すと、所定の短い社員の単価が法定を下回る。
     */
    private Duration annualScheduledTimeOf(int fiscalYear, DateRange period,
                                           CompanyCalendar registered) {
        List<WorkRuleSeriesUsage> usages = series.findUsagesIn(period);
        if (usages.isEmpty()) {
            throw new WorkRuleNotInUseException(fiscalYear);
        }

        Map<WorkRuleSeriesId, List<WorkRule>> versions = new LinkedHashMap<>();
        Duration total = Duration.ZERO;
        for (LocalDate date = period.from(); date.isBefore(period.toExclusive());
                date = date.plusDays(1)) {
            if (registered.dayTypeOf(date) != DayType.WORKDAY) {
                continue;
            }
            total = total.plus(scheduledOn(fiscalYear, date, usages, versions));
        }
        return total;
    }

    /**
     * その日の会社の所定労働時間。
     *
     * <p><strong>系列ごとに 1 年ぶんを足してから比べない。</strong>
     * 年度の途中で新設した系列は 4 月〜使用開始前日に版を持たないのが正常であり、
     * 年度の全所定労働日について版を要求すると
     * <strong>10 月からフレックスを導入した会社は FY のどの月も出力できなくなる</strong>
     * （落とし穴 131）。日ごとに「その日に適用されている系列」だけを見る。
     *
     * <p>日ごとに見ると、<strong>年度の途中で全社の所定を変えた年度</strong>も正しく数えられる。
     * 4 月〜9 月が 480 分・10 月〜3 月が 465 分なら、その日ごとの値を足したものが年間の所定である。
     *
     * <p>拒否するのは<strong>同じ日に所定が 2 つ以上ある</strong>場合だけである。
     * 労基則 19 条 1 項 4 号の分母は「その労働者の」所定なので、
     * 割れたまま 1 つの値を返すと、所定の短い社員の単価が法定を下回る。
     */
    private Duration scheduledOn(int fiscalYear, LocalDate date,
                                 List<WorkRuleSeriesUsage> usages,
                                 Map<WorkRuleSeriesId, List<WorkRule>> versions) {
        Map<WorkRuleSeriesId, Duration> onThatDay = new LinkedHashMap<>();
        for (WorkRuleSeriesUsage usage : usages) {
            if (!usage.period().contains(date)) {
                continue;
            }
            List<WorkRule> found = versions.computeIfAbsent(usage.seriesId(),
                    workRules::findVersionsOf);
            WorkRule effective = effectiveOn(found, date)
                    // ★ 版の隙間を 0 として素通りさせない。年間の所定が過少に出て、
                    //   分母が小さくなり単価が過大になる（法定は下回らないが値は誤り）。
                    //   ここは「適用されているのに版が無い」日なので、本当に隙間である
                    .orElseThrow(() -> new WorkRuleVersionMissingException(fiscalYear,
                            usage.seriesId(), date));
            onThatDay.put(usage.seriesId(), effective.scheduledDailyWorkingTime());
        }
        if (onThatDay.isEmpty()) {
            // 誰にも就業規則が適用されていない所定労働日。会社の所定が決まらない
            throw new WorkRuleNotAppliedOnException(fiscalYear, date);
        }
        Set<Duration> distinct = Set.copyOf(onThatDay.values());
        if (distinct.size() > 1) {
            throw new MultipleScheduledWorkingTimesException(fiscalYear, date, onThatDay);
        }
        return distinct.iterator().next();
    }

    /**
     * その日に有効な版。
     *
     * <p><strong>「どの系列の」を引数で受け取る。</strong>
     * 主体を取らない解決関数は、他人の規則を黙って返す形になる（CLAUDE.md 落とし穴 42）。
     */
    private Optional<WorkRule> effectiveOn(List<WorkRule> versions, LocalDate date) {
        List<WorkRule> found = versions.stream()
                .filter(version -> version.validPeriod().contains(date))
                .toList();
        if (found.size() > 1) {
            throw new IllegalStateException(
                    "同じ日に有効な版が 2 件以上あります: %s / %s".formatted(date, found));
        }
        return found.stream().findFirst();
    }

    /**
     * 年度のカレンダーが登録されているか。
     *
     * <p><strong>未登録の日は所定労働日として扱われる</strong>（登録漏れを休日と誤ると
     * 通常勤務に休日割増が付いて過払いになるため）。
     * したがって登録していない年度は所定労働日数が暦日数に近づき、
     * <strong>分母が 1.5 倍・単価が法定を約 33% 下回る。</strong>
     *
     * <p><strong>「法定休日が 1 日も無い」では足りない。</strong>
     * 4 月だけ登録した年度は法定休日を 4 日持つので通過するが、
     * 所定労働日数は 357 日になり単価が 27% 低く出る。
     * 欠けている日そのものを集めて、1 日でも欠けていれば拒否する。
     */
    private void requireCalendarRegistered(int fiscalYear, DateRange period,
                                           RegisteredCalendar registered) {
        List<LocalDate> missing = registered.missingDates(period);
        if (!missing.isEmpty()) {
            throw new CalendarNotRegisteredException(fiscalYear, missing);
        }
    }

    /**
     * 年間の所定が法定の総枠に収まるか（労基法 32 条）。
     *
     * <p>全日を所定労働日として登録した年度を捕まえる。
     * 登録の抜けが無くても<strong>中身が誤っている</strong>ことはあり、
     * 前の検査だけでは通ってしまう。
     *
     * <p>閾値は法から出す。<strong>「登録日数が 100 日未満なら」のような数字を置かない</strong>
     * （CLAUDE.md「法定値の制約」）。
     *
     * <p><strong>週の数は切り上げる。</strong>
     * 32 条が定めるのは 1 週の上限であって年間の上限ではない。
     * 年度は週の整数倍ではないので（365 日 = 52 週 + 1 日）、
     * {@code 暦日数 ÷ 7} で数えると<strong>端数の週を数え落とす。</strong>
     * 土日を休みにしただけの正当なカレンダーが
     * 261 日 × 8 時間 = 2,088 時間となり、切り捨てた総枠 2,085 時間 42 分を超えて
     * <strong>拒否されてしまう</strong>（落とし穴 23・51）。
     * 端数の週も 1 週と数えて 53 週 = 2,120 時間を上限にする。
     *
     * <p>この上限は緩い。狙いは
     * <strong>全日を所定労働日として登録した年度</strong>（2,920 時間）のような
     * 明らかな誤りを捕まえることであり、週ごとの上限は
     * {@code schedule-exceeds-statutory-limit} の警告が別に担う。
     */
    private void requireWithinStatutoryYear(int fiscalYear, DateRange period,
                                            Duration annualTotal) {
        long weeks = (period.days() + 6) / 7;
        Duration limit = WorkRule.STATUTORY_WEEKLY.multipliedBy(weeks);
        if (annualTotal.compareTo(limit) > 0) {
            throw new CalendarExceedsStatutoryYearException(fiscalYear, annualTotal, limit);
        }
    }

    /**
     * 社員に就業規則を適用する。
     *
     * <p><strong>過去へ遡って適用できない</strong>（適用開始日が締め済みの月に入る場合）。
     * 遡らせると、確定済みの月の所定労働時間が後から変わる。
     *
     * <p><strong>分母の入力はカレンダーだけではない。</strong>
     * 年間の所定労働時間は「所定労働日 × その日に適用されている規則の所定」なので、
     * 適用を変えると出力済みの年度の分母も動きうる。
     * カレンダーと同じ検査を当てる（落とし穴 130）。
     */
    @Transactional
    public void assignWorkRule(Requester requester, EmployeeId employeeId,
                            WorkRuleSeriesId seriesId, LocalDate validFrom) {
        requireHumanResources(requester);
        YearMonth month = YearMonth.from(validFrom);
        if (monthClosure.isClosed(employeeId, month)) {
            throw MonthClosureQuery.MonthAlreadyClosedException.affecting(month, "就業規則の適用");
        }
        // ★ その日に有効な版を持たない系列へは適用しない。
        //   適用だけがあって版が無い日は「規則が引けない日」になり、
        //   その社員はその月を提出できない（work-rule-not-assigned）。
        //   しかも `unassigned` の一覧は適用の有無しか見ないので正常に見える。
        //   気づくのは月末に提出しようとしたときである
        boolean hasVersion = workRules.findVersionsOf(seriesId).stream()
                .anyMatch(version -> version.validPeriod().contains(validFrom));
        if (!hasVersion) {
            throw new NoEffectiveVersionException(seriesId, validFrom);
        }

        series.assign(employeeId, seriesId, validFrom);
        // ★ 適用は期限を持たないので、以後のすべての年度に効く
        requireDivisorUnchangedFrom(validFrom);
    }

    /**
     * 就業規則を新規に登録する（系列 + 初版。API 設計書 1 の一覧）。
     *
     * <p><strong>締め済みの月を拒まない。</strong>
     * 新しい系列はまだ誰にも適用されていないので、
     * 確定済みの勤怠を 1 件も動かさない。
     * 拒むのは<strong>適用</strong>（{@code assignWorkRule}）の側であり、
     * そこには締めの検査がある。
     * ここで拒むと、過去に遡って規則を整備することが永久にできなくなる。
     *
     * <p>同じ理由で<strong>分母の検査も要らない</strong>。
     * 分母は「その日に適用されている系列」から求めるので（落とし穴 131）、
     * 適用されていない系列は分母に入らない。
     */
    @Transactional
    public RegisteredWorkRule registerWorkRule(Requester requester, String name,
                                        WorkRuleSpec spec) {
        requireHumanResources(requester);
        requireName(name);
        WorkRuleSeriesId seriesId = new WorkRuleSeriesId(UUID.randomUUID());
        series.save(WorkRuleSeries.active(seriesId, name.strip()));
        WorkRule initial = spec.toWorkRule(seriesId, DateRange.startingAt(spec.validFrom()));
        workRules.save(initial);
        // ★ 版は読み直して返す。手で 1 と書くと、DB が 0 から始めていたときに
        //   応答だけが嘘をつき、次の改定が必ず 409 になる
        long version = series.findById(seriesId).map(WorkRuleSeries::version).orElse(0L);
        return new RegisteredWorkRule(seriesId, initial.id(), version,
                capacityWarnings(spec, spec.validFrom()));
    }

    /**
     * 就業規則を改定する（API 設計書 2.2）。
     *
     * <p><strong>既存の版を書き換えない。現行版を閉じて、新しい版を足す。</strong>
     * 書き換えると、過去の勤怠が当時とは違う規則で再計算される。
     *
     * <p><strong>社員の適用行は一切触らない。</strong>
     * 適用は系列を指しているので、新しい版は {@code validFrom} 以降で自動的に選ばれる
     * （落とし穴 13）。
     *
     * <p><strong>閉じてから入れる。</strong> 入れるだけにすると期間が重なり、
     * 排他制約に弾かれて<strong>一度作った系列を二度と改定できなくなる</strong>
     * （落とし穴 73）。
     */
    @Transactional
    public RegisteredWorkRule reviseWorkRule(Requester requester, WorkRuleSeriesId seriesId,
                                        long expectedVersion, WorkRuleSpec spec) {
        requireHumanResources(requester);
        WorkRuleSeries target = series.findById(seriesId)
                .orElseThrow(() -> new WorkRuleSeriesNotFoundException(seriesId));
        if (!target.isActiveOn(spec.validFrom())) {
            throw new AbolishedWorkRuleSeriesException(seriesId);
        }
        // ★ 締め済みの月を拒む。所定が変われば確定済みの月次清算と矛盾する
        YearMonth month = YearMonth.from(spec.validFrom());
        if (monthClosure.isClosedForAnyone(month)) {
            throw MonthClosureQuery.MonthAlreadyClosedException.affecting(month, "就業規則");
        }

        List<WorkRule> versions = workRules.findVersionsOf(seriesId);
        requireRevisable(seriesId, versions, spec.validFrom());

        // ★ 月中の改定は、月次清算に効く値を変えないものに限る
        requireMonthlyBasisUnchangedMidMonth(seriesId, versions, spec);

        // ★ 版を先に進める。進められなければ誰かが先に改定しているので、
        //   行を 1 つも書かずに終わる
        if (!series.bumpVersion(seriesId, expectedVersion)) {
            throw new org.springframework.dao.OptimisticLockingFailureException(
                    "就業規則の系列 %s は版 %d ではありません".formatted(seriesId.value(),
                            expectedVersion));
        }

        // ★ 閉じるのが先。順序の保証は永続化に閉じ込める（落とし穴 73）
        List<WorkRule> closed = versions.stream()
                .filter(current -> current.validPeriod().contains(spec.validFrom()))
                .map(current -> closedAt(current, spec.validFrom()))
                .toList();
        WorkRule added = spec.toWorkRule(seriesId, DateRange.startingAt(spec.validFrom()));
        workRules.revise(closed, added);

        // ★ 所定が変われば年度の分母が動く。出力済みの年度は守る（落とし穴 133）
        requireDivisorUnchangedFrom(spec.validFrom());
        return new RegisteredWorkRule(seriesId, added.id(), expectedVersion + 1,
                capacityWarnings(spec, spec.validFrom()));
    }

    /** 現行版を {@code at} で閉じた同じ版。識別子は変えない（同じ行を更新する）。 */
    private static WorkRule closedAt(WorkRule current, LocalDate at) {
        return new WorkRule(current.id(), current.seriesId(),
                new DateRange(current.validPeriod().from(), at),
                current.workingTimeSystem(), current.statutoryDailyWorkingTime(),
                current.statutoryWeeklyWorkingTime(), current.nightWindow(),
                current.premiumRates());
    }

    /**
     * 改定できる開始日か。
     *
     * <p><strong>指定日以降に別の版があるなら拒む</strong>（409）。
     * 間に割り込ませると、あとの版の開始日と重なるか、
     * あとの版を黙って無効にすることになる。
     */
    private static void requireRevisable(WorkRuleSeriesId seriesId, List<WorkRule> versions,
                                         LocalDate validFrom) {
        for (WorkRule version : versions) {
            if (!version.validPeriod().from().isBefore(validFrom)) {
                throw new OverlappingWorkRulePeriodException(seriesId, validFrom);
            }
        }
    }

    /**
     * 所定総労働時間が法定の総枠を超える月（API 設計書 2.2）。
     *
     * <p><strong>拒否しない。</strong> 適法な状態なので登録は許し、人事に知らせる。
     * 拒むと、実際にそういう規則を運用している会社が登録できなくなる。
     *
     * <p>固定時間制では起きない（所定は 1 日ごとに法定内へ収まる）。
     * 見るのはフレックスだけである。
     *
     * <p><strong>カレンダーが登録されていない月は飛ばす。</strong>
     * 未登録の日は所定労働日として扱われる（{@code RegisteredCalendar} の既定）ので、
     * 登録済みの範囲の先を見ると
     * <strong>暦日すべてが所定労働日の月</strong>として数えられ、
     * 30 日 × 8 時間 = 240 時間が総枠 171 時間を超えて<strong>必ず警告が立つ。</strong>
     * 12 か月ぶん見る以上、登録の先へ必ず出るので、
     * 飛ばさないと警告が雑音になって読まれなくなる（落とし穴 123 と同型で、
     * 同じ既定値が別の式では危険側に倒れる）。
     */
    private List<ScheduleCapacityWarning> capacityWarnings(WorkRuleSpec spec,
                                                           LocalDate from) {
        if (!(spec.system() instanceof FlextimeSystem flex)) {
            return List.of();
        }
        YearMonth first = YearMonth.from(from);
        DateRange span = new DateRange(first.atDay(1), first.plusMonths(12).atDay(1));
        RegisteredCalendar registered = new RegisteredCalendar(calendar.findByPeriod(span));
        List<ScheduleCapacityWarning> found = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            YearMonth month = first.plusMonths(i);
            DateRange whole = DateRange.ofMonth(month);
            if (!registered.missingDates(whole).isEmpty()) {
                // 未登録の日を所定労働日として数えると、必ず総枠を超える
                continue;
            }
            int workdays = registered.workdayCountIn(whole);
            // 暦月そのものを清算期間として見る。ここは会社の規則の話であり、
            // 特定の社員の在籍期間では切らない
            SettlementPeriod.of(month, whole)
                    .flatMap(period ->
                            period.checkCapacity(flex, workdays, spec.statutoryWeekly()))
                    .ifPresent(found::add);
        }
        return found;
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new InvalidCalendarRequestException("就業規則の名称は必須です");
        }
    }

    /**
     * 登録・改定の入力。
     *
     * <p><strong>制度は {@code sealed interface} のまま受け取る。</strong>
     * 平坦な項目で受けると「FLEX なのに始業時刻がある」形を作れてしまい、
     * DB の CHECK 制約が禁じた状態を API が再現する。
     */
    public record WorkRuleSpec(LocalDate validFrom, WorkingTimeSystem system,
                               Duration statutoryDaily, Duration statutoryWeekly,
                               NightWindow nightWindow, PremiumRates premiumRates) {

        WorkRule toWorkRule(WorkRuleSeriesId seriesId, DateRange validPeriod) {
            return new WorkRule(new WorkRuleId(UUID.randomUUID()), seriesId, validPeriod,
                    system, statutoryDaily, statutoryWeekly, nightWindow, premiumRates);
        }
    }

    /**
     * 登録・改定の結果。
     *
     * <p>版は<strong>系列</strong>のもの。次の改定にそのまま渡せる。
     */
    public record RegisteredWorkRule(WorkRuleSeriesId seriesId, WorkRuleId workRuleId,
                                     long version, List<ScheduleCapacityWarning> warnings) {
    }

    /** その系列が無い。 */
    public static final class WorkRuleSeriesNotFoundException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        WorkRuleSeriesNotFoundException(WorkRuleSeriesId id) {
            super("就業規則が見つかりません: " + id.value());
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:resource-not-found";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.NOT_FOUND;
        }

        @Override
        public String title() {
            return "就業規則が見つかりません";
        }
    }

    /** 廃止済みの系列は改定できない。 */
    public static final class AbolishedWorkRuleSeriesException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        AbolishedWorkRuleSeriesException(WorkRuleSeriesId id) {
            super("廃止済みの就業規則は改定できません: " + id.value());
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:abolished-work-rule-series";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.RULE_VIOLATION;
        }

        @Override
        public String title() {
            return "廃止済みの就業規則です";
        }
    }

    /**
     * 月の途中の改定が、月次清算に効く値を変えていないことを確かめる。
     *
     * <p><strong>月中の改定そのものは禁じない。</strong>
     * 社員は系列を指しているので、版を足しても適用は切れない（ADR 0003）。
     * 日次計算は日ごとに版を引くので、深夜帯や割増率を月中から変えるのは正しく動く。
     *
     * <p>禁じるのは<strong>所定労働時間・法定労働時間・労働時間制度</strong>を
     * 月の途中から変えることである。月次清算は 1 か月を 1 つの版で計算するので、
     * これらが月中で割れると<strong>所定総労働時間も不足時間も法定総枠も
     * 片方の版の値だけで求まる。</strong>
     * フレックスの清算期間は労使協定が定めた起算日から 1 か月であり（労基法 32 条の 3）、
     * その途中で所定を差し替えること自体が制度の前提に反する。
     *
     * <p><strong>ここで拒まないと、月次清算の側が拒むことになる。</strong>
     * そうなるとその月は提出も承認も締めもできず、
     * 規則を戻す以外に出口の無い月が残る（落とし穴 26・93）。
     */
    private static void requireMonthlyBasisUnchangedMidMonth(
            WorkRuleSeriesId seriesId, List<WorkRule> versions, WorkRuleSpec spec) {
        if (spec.validFrom().getDayOfMonth() == 1) {
            return;
        }
        WorkRule added = spec.toWorkRule(seriesId, DateRange.startingAt(spec.validFrom()));
        for (WorkRule current : versions) {
            if (current.validPeriod().contains(spec.validFrom())
                    && !current.hasSameMonthlyBasisAs(added)) {
                throw new MonthlyBasisChangedMidMonthException(spec.validFrom());
            }
        }
    }

    /** 月の途中の改定が、月次清算に効く値を変えている。 */
    public static final class MonthlyBasisChangedMidMonthException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        MonthlyBasisChangedMidMonthException(LocalDate validFrom) {
            super("所定労働時間・法定労働時間・労働時間制度を変える改定は月初日からに限ります: "
                    + validFrom);
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:monthly-basis-changed-mid-month";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.RULE_VIOLATION;
        }

        @Override
        public String title() {
            return "所定を変える改定は月初日からに限ります";
        }
    }

    /** 指定日以降に既に別の版がある。 */
    public static final class OverlappingWorkRulePeriodException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        OverlappingWorkRulePeriodException(WorkRuleSeriesId id, LocalDate validFrom) {
            super("%s 以降に既に別の版があります: %s".formatted(validFrom, id.value()));
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:overlapping-period";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.CONFLICT;
        }

        @Override
        public String title() {
            return "期間が重なります";
        }
    }

    private static void requireHumanResources(Requester requester) {
        if (!requester.has(Role.HR)) {
            throw new AccessDeniedException();
        }
    }

    /**
     * その年度の分母を使った給与連携の出力が済んでいる（BR-18）。
     *
     * <p>年度の所定労働日数が変わると、既に払った割増賃金の単価が事後的に変わる。
     */
    public static final class FiscalYearUsedByPayrollException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        FiscalYearUsedByPayrollException(int fiscalYear) {
            super("%d 年度は給与連携で出力済みです。この変更は年間の所定労働時間を動かすので、"
                    .formatted(fiscalYear)
                    + "既に払った割増賃金の単価が事後的に変わります");
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:fiscal-year-used-by-payroll";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.CONFLICT;
        }

        @Override
        public String title() {
            return "給与連携で出力済みの年度です";
        }
    }

    /**
     * 年度のカレンダーが登録されていない（BR-18）。
     *
     * <p>未登録の日は所定労働日として扱われるので、
     * <strong>割増賃金の基礎額の分母が過大になり、単価が法定を下回る。</strong>
     */
    public static final class CalendarNotRegisteredException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final transient List<LocalDate> missingDates;

        CalendarNotRegisteredException(int fiscalYear, List<LocalDate> missingDates) {
            super("%d 年度の会社カレンダーに未登録の日が %d 件あります（最初は %s）"
                    .formatted(fiscalYear, missingDates.size(), missingDates.get(0)));
            this.missingDates = List.copyOf(missingDates);
        }

        /** 未登録の日。<strong>先頭の 10 件だけ返す</strong>（365 件を応答に載せない）。 */
        public List<LocalDate> missingDates() {
            return missingDates.stream().limit(10).toList();
        }

        public int missingCount() {
            return missingDates.size();
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:calendar-not-registered";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.RULE_VIOLATION;
        }

        @Override
        public String title() {
            return "年度の会社カレンダーが登録されていません";
        }
    }

    /**
     * 年間の所定労働時間が法定の総枠を超えている（労基法 32 条）。
     *
     * <p>カレンダーの登録に抜けが無くても、<strong>中身が誤っている</strong>ことはある。
     * 全日を所定労働日として登録した年度がこれで捕まる。
     */
    public static final class CalendarExceedsStatutoryYearException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        CalendarExceedsStatutoryYearException(int fiscalYear, Duration total, Duration limit) {
            super("%d 年度の所定労働時間が法定の総枠を超えています: %s > %s"
                    .formatted(fiscalYear, total, limit));
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:calendar-exceeds-statutory-year";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.RULE_VIOLATION;
        }

        @Override
        public String title() {
            return "年度の所定労働時間が法定の総枠を超えています";
        }
    }

    /**
     * 年度に適用されている就業規則の所定が 1 つに定まらない（BR-18）。
     *
     * <p>要件 3.1 は全社員 1 日 8 時間と定めているが、
     * DB は所定を法定の 8 時間を上限とする 1 分単位の値として持てる。
     * <strong>割れたまま 1 つの分母を返すと、所定の短い社員の単価が法定を下回る。</strong>
     */
    public static final class MultipleScheduledWorkingTimesException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        MultipleScheduledWorkingTimesException(int fiscalYear, LocalDate date,
                                               Map<WorkRuleSeriesId, Duration> onThatDay) {
            super("%d 年度の %s に所定労働時間の異なる就業規則が並んでいます: %s（要件 3.1 は全社員 1 日 8 時間と定めています）"
                    .formatted(fiscalYear, date, onThatDay.values()));
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:multiple-scheduled-working-times";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.RULE_VIOLATION;
        }

        @Override
        public String title() {
            return "年間の所定労働時間が就業規則によって異なります";
        }
    }

    /** 年度に適用されている就業規則が 1 つも無い。 */
    /**
     * 所定労働日なのに、その日は誰にも就業規則が適用されていない。
     *
     * <p>{@link WorkRuleNotInUseException} が「年度に 1 件も無い」なのに対し、
     * こちらは<strong>年度の一部だけ空いている</strong>場合である。
     * 導入初年度（1 月から使い始めた会社の FY 前半）に必ず起こる。
     *
     * <p>0 時間として素通りさせない。年間の所定が過少に出て分母が小さくなり、
     * <strong>単価が過大</strong>になる。法定は下回らないが、値としては誤りである。
     */
    public static final class WorkRuleNotAppliedOnException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        WorkRuleNotAppliedOnException(int fiscalYear, LocalDate date) {
            super("%d 年度の %s は所定労働日ですが、就業規則が誰にも適用されていません"
                    .formatted(fiscalYear, date));
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:work-rule-not-applied-on";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.RULE_VIOLATION;
        }

        @Override
        public String title() {
            return "所定労働日に就業規則が適用されていません";
        }
    }

    /** 年度に所定労働日が 1 日も無い。分母が 0 になるので出力できない。 */
    public static final class NoScheduledWorkdayException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        NoScheduledWorkdayException(int fiscalYear) {
            super("%d 年度に所定労働日が 1 日もありません".formatted(fiscalYear));
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:no-scheduled-workday";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.RULE_VIOLATION;
        }

        @Override
        public String title() {
            return "年度に所定労働日がありません";
        }
    }

    public static final class WorkRuleNotInUseException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        WorkRuleNotInUseException(int fiscalYear) {
            super("%d 年度に適用されている就業規則がありません".formatted(fiscalYear));
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:work-rule-not-in-use";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.RULE_VIOLATION;
        }

        @Override
        public String title() {
            return "年度に適用されている就業規則がありません";
        }
    }

    /** 年度の途中で就業規則の版が欠けている。 */
    public static final class WorkRuleVersionMissingException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        WorkRuleVersionMissingException(int fiscalYear, WorkRuleSeriesId seriesId,
                                        LocalDate from) {
            super("%d 年度（%s 開始）に版の無い日がある就業規則があります: %s"
                    .formatted(fiscalYear, from, seriesId.value()));
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:work-rule-version-missing";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.RULE_VIOLATION;
        }

        @Override
        public String title() {
            return "年度に版の無い日がある就業規則があります";
        }
    }

    /** その日に有効な版を持たない系列を適用しようとした。 */
    public static final class NoEffectiveVersionException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        NoEffectiveVersionException(WorkRuleSeriesId seriesId, LocalDate validFrom) {
            super("その日に有効な版がありません: 系列 %s / 適用開始日 %s"
                    .formatted(seriesId.value(), validFrom));
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:no-effective-work-rule-version";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.RULE_VIOLATION;
        }

        @Override
        public String title() {
            return "その日に有効な版がありません";
        }
    }
}
