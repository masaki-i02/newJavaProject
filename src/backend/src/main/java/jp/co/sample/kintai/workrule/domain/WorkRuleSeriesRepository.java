package jp.co.sample.kintai.workrule.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/** 就業規則の系列と、社員への適用のポート。 */
public interface WorkRuleSeriesRepository {

    Optional<WorkRuleSeries> findById(WorkRuleSeriesId id);

    List<WorkRuleSeries> findAll();

    void save(WorkRuleSeries series);

    /**
     * 系列の版を 1 つ進める。改定を積んだことを表す。
     *
     * <p><strong>版は系列にだけ持たせる</strong>（API 設計書 2.1）。
     * 更新の対象は系列（改称・廃止）と改定であり、版そのものは追記されるだけで
     * 書き換えない。
     *
     * @return 進められたら {@code true}。<strong>{@code false} は
     *         「その版はもう古い」ことを意味する</strong>（誰かが先に改定した）
     */
    boolean bumpVersion(WorkRuleSeriesId id, long expectedVersion);

    /**
     * 社員に系列を適用する。
     *
     * <p><strong>指すのは版ではなく系列である。</strong>
     * 版を直接指すと、改定した瞬間に指し先が「過去の版」になり、
     * 全社員の勤怠計算が停止する（ADR 0003）。
     */
    void assign(EmployeeId employeeId, WorkRuleSeriesId seriesId, LocalDate validFrom);

    List<WorkRuleAssignment> findAssignments(EmployeeId employeeId);

    /**
     * 指定日に規則が適用されていない在籍者。
     *
     * <p>「在籍者全員に規則が適用されている」ことは DB では守れない。
     * 画面で検知するために置く。
     */
    List<EmployeeId> findEmployeesWithoutRuleOn(LocalDate date);

    /**
     * 期間に<strong>実際に適用されている</strong>系列と、その適用範囲（BR-18）。
     *
     * <p>割増賃金の基礎額の分母（労基則 19 条 1 項 4 号）は、
     * その年度に使われている就業規則から導く。
     * {@link #findAll()} で全系列を舐めると、<strong>廃止済みの系列や過去の版まで巻き込み</strong>、
     * 一度でも違う所定の規則を作ったことがある会社は永久に出力できなくなる。
     *
     * <p><strong>系列だけでなく適用範囲を返す。</strong>
     * 系列の一覧だけを渡すと、呼び出す側は「年度の全所定労働日にその系列の版がある」
     * ことを前提にするしかない。年度の途中で新設した系列は 4 月に版を持たないのが正常なので、
     * その前提は<strong>導入 2 年目以降のすべての年度で崩れる</strong>（落とし穴 131）。
     */
    List<WorkRuleSeriesUsage> findUsagesIn(DateRange period);
}
