package jp.co.sample.kintai.workrule.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/** 就業規則のポート。実装は {@code infrastructure}。 */
public interface WorkRuleRepository {

    /**
     * 指定日にその社員へ適用される版（時点解決）。
     *
     * <p><strong>社員を引数で受け取る。</strong> 適用は社員ごとなので、
     * 誰の規則かを渡さない形にすると他人の規則を黙って返しうる
     * （CLAUDE.md 落とし穴 42）。
     */
    Optional<WorkRule> findEffective(EmployeeId employeeId, LocalDate date);

    /**
     * 期間分をまとめて解決する。
     *
     * <p><strong>月次の集計で日ごとに問い合わせると N+1 になる。</strong>
     * 期間の分を 1 度で取得し、メモリ上で引く。
     * 規則が適用されていない日はキーごと現れない。
     */
    Map<LocalDate, WorkRule> findEffectiveByPeriod(EmployeeId employeeId, DateRange period);

    Optional<WorkRule> findById(WorkRuleId id);

    /** その系列の版の履歴。有効期間の昇順。 */
    List<WorkRule> findVersionsOf(WorkRuleSeriesId seriesId);

    void save(WorkRule workRule);
}
