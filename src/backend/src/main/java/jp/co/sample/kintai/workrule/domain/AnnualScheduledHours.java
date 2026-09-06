package jp.co.sample.kintai.workrule.domain;

import java.time.Duration;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;

import jp.co.sample.kintai.shared.domain.DateRange;

/**
 * 年度の所定と、割増賃金の基礎額に使う 1 か月平均所定労働時間数。
 *
 * <p>労基則 19 条 1 項 4 号は、月給の場合の割増賃金の基礎額を
 * <strong>「1 年間における 1 か月平均所定労働時間数」</strong>で除して求めると定める。
 * この値は会社カレンダーからしか出せず、<strong>1 社員 × 1 か月の行には入らない</strong>ので、
 * 月次の CSV とは別に年ごとの 1 行として渡す（CLAUDE.md 落とし穴 118）。
 *
 * @param fiscalYear     年度。<strong>4 月 〜 翌 3 月</strong>。36 協定の年度（BR-12）にそろえる
 * @param period         数えた範囲。「年度」の解釈は会社によって違うので明示する
 * @param scheduledDays  年間の所定労働日数。<strong>年休は差し引かない</strong>（会社の所定である）
 * @param annualTotal    年間の所定労働時間。所定労働日について、その日に有効な版の所定を足したもの
 * @param monthlyAverage 1 か月平均所定労働時間数。<strong>{@code annualTotal ÷ 12}（分未満切り捨て）</strong>
 */
public record AnnualScheduledHours(int fiscalYear, DateRange period, int scheduledDays,
                                   Duration annualTotal, Duration monthlyAverage) {

    /** 年度の開始月。36 協定の年度（BR-12）にそろえる。 */
    public static final Month FISCAL_YEAR_START = Month.APRIL;

    private static final int MONTHS_IN_YEAR = 12;

    public AnnualScheduledHours {
        if (period == null || annualTotal == null || monthlyAverage == null) {
            throw new IllegalArgumentException("年度の所定の項目に null は許されません");
        }
        if (scheduledDays < 0) {
            throw new IllegalArgumentException("所定労働日数を負にはできません: " + scheduledDays);
        }
        if (monthlyAverage.isNegative() || annualTotal.isNegative()) {
            throw new IllegalArgumentException("所定労働時間を負にはできません: " + annualTotal);
        }
        // ★ 導出できる値を持つので、食い違いを型でも禁じる（落とし穴 39）。
        //   DB の payroll_exports_average_derivation_check と同じ式である
        if (monthlyAverage.toMinutes() != annualTotal.toMinutes() / MONTHS_IN_YEAR) {
            throw new IllegalArgumentException(
                    "月平均が年間の所定 ÷ 12 と一致しません: %s / %s"
                            .formatted(monthlyAverage, annualTotal));
        }
    }

    /**
     * 年間の所定労働時間から組み立てる。
     *
     * <p><strong>「日数 × 1 日の所定」を引数に取らない。</strong>
     * その式は「1 日の所定が年度を通じて一定」を暗黙に仮定しており、
     * 年度の途中で就業規則が改定されると黙って誤った分母を返す。
     * 日ごとに足した合計を受け取る。
     *
     * <p><strong>分未満は切り捨てる。</strong>
     * この値は割増賃金の基礎額の<strong>分母</strong>なので、大きくすると単価が下がる。
     * 切り上げると法定を下回る側へ倒れる。
     *
     * <p>1 日の所定が 8 時間ちょうど（480 分）のあいだ、
     * <strong>切り捨ては一度も起きない</strong>（480 ÷ 12 = 40 で必ず割り切れる）。
     * 就業規則は 1 分単位で所定を持てるので、7 時間 45 分（465 分）のような
     * 12 の倍数でない所定を登録した年度で初めて効く。
     *
     * @param annualTotal 年間の所定労働時間。所定労働日について、その日に有効な版の所定を足したもの
     */
    public static AnnualScheduledHours of(int fiscalYear, int scheduledDays,
                                          Duration annualTotal) {
        if (annualTotal == null || annualTotal.isNegative()) {
            throw new IllegalArgumentException("年間の所定労働時間が不正です: " + annualTotal);
        }
        Duration monthlyAverage =
                Duration.ofMinutes(annualTotal.toMinutes() / MONTHS_IN_YEAR);
        return new AnnualScheduledHours(fiscalYear, periodOf(fiscalYear),
                scheduledDays, annualTotal, monthlyAverage);
    }

    /** 年度の範囲。半開区間 {@code [4/1, 翌 4/1)}。 */
    public static DateRange periodOf(int fiscalYear) {
        LocalDate from = LocalDate.of(fiscalYear, FISCAL_YEAR_START, 1);
        return new DateRange(from, from.plusYears(1));
    }

    /**
     * その月が属する年度。
     *
     * <p>1 月〜3 月は<strong>前の年</strong>の年度である。
     * 暦年と混ぜると、同じ月がどちらの年度かで違う分母を持つことになる。
     */
    public static int fiscalYearOf(YearMonth month) {
        return month.getMonthValue() < FISCAL_YEAR_START.getValue()
                ? month.getYear() - 1
                : month.getYear();
    }
}
