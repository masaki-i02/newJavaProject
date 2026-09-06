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
    public void 暦日区分を設定する(Requester requester, LocalDate date, DayType dayType,
                            String name) {
        requireHumanResources(requester);
        YearMonth month = YearMonth.from(date);
        if (monthClosure.isClosedForAnyone(month)) {
            throw new MonthAlreadyClosedException(month, "会社カレンダー");
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
    public CalendarRegistration 暦日区分をまとめて設定する(
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
                throw new MonthAlreadyClosedException(month, "会社カレンダー");
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
    public void 就業規則を適用する(Requester requester, EmployeeId employeeId,
                            WorkRuleSeriesId seriesId, LocalDate validFrom) {
        requireHumanResources(requester);
        YearMonth month = YearMonth.from(validFrom);
        if (monthClosure.isClosed(employeeId, month)) {
            throw new MonthAlreadyClosedException(month, "就業規則の適用");
        }
        series.assign(employeeId, seriesId, validFrom);
        // ★ 適用は期限を持たないので、以後のすべての年度に効く
        requireDivisorUnchangedFrom(validFrom);
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

    /**
     * 締め済みの月に影響する変更。
     *
     * <p><strong>状態が変われば通るので {@code CONFLICT}（409）。</strong>
     * ただし締めを戻す手段は用意していないので、実際には通らない。
     */
    public static final class MonthAlreadyClosedException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        MonthAlreadyClosedException(YearMonth month, String 対象) {
            super("締め済みの月に影響するため%sを変更できません: %s".formatted(対象, month));
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
            return "締め済みの月に影響する変更はできません";
        }
    }
}
