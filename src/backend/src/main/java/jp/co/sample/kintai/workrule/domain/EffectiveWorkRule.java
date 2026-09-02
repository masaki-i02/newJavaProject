package jp.co.sample.kintai.workrule.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * 指定日に社員へ適用される就業規則を決める（時点解決）。
 *
 * <p>解決は 2 段階である。
 * <pre>
 * employeeId + date ──&gt; 適用（assignment）──&gt; 系列
 *                                              │ + date
 *                                              ▼
 *                                          版（WorkRule）
 * </pre>
 *
 * <p>版を直接指していると、改定した瞬間に指し先が「過去の版」になり、
 * <strong>全社員の勤怠計算が停止する</strong>（ADR 0003）。
 */
public final class EffectiveWorkRule {

    private EffectiveWorkRule() {
    }

    /**
     * 指定日に有効な版を返す。
     *
     * <p><strong>誰の規則かを引数で受け取る。</strong>
     * 受け取らずに「その社員の適用だけが渡される」ことを前提にすると、
     * 別の社員の適用を渡されたときに<strong>他人の規則を黙って返す</strong>。
     * 複数人ぶんをまとめて渡されたときも、
     * 正当なデータなのに「制約が壊れた」という誤診断になる。
     *
     * @param employeeId  対象の社員
     * @param assignments 適用の履歴。他の社員のものが混ざっていてもよい
     * @param versions    版の履歴。他の系列のものが混ざっていてもよい
     * @param date        対象日
     * @return 適用が無い、または版に隙間がある場合は空。<strong>例外にしない</strong>
     */
    public static Optional<WorkRule> resolve(EmployeeId employeeId,
                                             List<WorkRuleAssignment> assignments,
                                             List<WorkRule> versions, LocalDate date) {
        if (employeeId == null || assignments == null || versions == null || date == null) {
            throw new IllegalArgumentException("時点解決の引数に null は許されません");
        }
        return only(assignments.stream()
                        .filter(assignment -> assignment.employeeId().equals(employeeId))
                        .filter(assignment -> assignment.period().contains(date))
                        .toList(),
                "適用", date)
                .flatMap(assignment -> only(versions.stream()
                                .filter(v -> v.seriesId().equals(assignment.seriesId()))
                                .filter(v -> v.validPeriod().contains(date))
                                .toList(),
                        "版", date));
    }

    /**
     * 系列の廃止も見たうえで解決する。
     *
     * <p>廃止した系列を指す適用が残っていると、廃止後の日付でも版が返る。
     * 版の有効期間は改定のたびに引き直されるので、
     * <strong>系列の廃止は版の有効期間には現れない。</strong>
     *
     * @param series 系列の一覧。対象の系列が見つからなければ空を返す
     */
    public static Optional<WorkRule> resolve(EmployeeId employeeId,
                                             List<WorkRuleAssignment> assignments,
                                             List<WorkRuleSeries> series,
                                             List<WorkRule> versions, LocalDate date) {
        if (series == null) {
            throw new IllegalArgumentException("系列の一覧に null は許されません");
        }
        return resolve(employeeId, assignments, versions, date)
                .filter(rule -> series.stream()
                        .filter(s -> s.id().equals(rule.seriesId()))
                        .findFirst()
                        .filter(s -> s.isActiveOn(date))
                        .isPresent());
    }

    /**
     * ただ 1 件であることを確かめて返す。0 件なら空。
     *
     * <p><strong>findFirst にしない。</strong>
     * 適用も版も期間が重ならないことを DB の {@code EXCLUDE} 制約で保証しているので、
     * 2 件見つかるのは制約が壊れたか、呼び出し側が壊れた履歴を渡したかである。
     * 先頭を黙って採ると、どちらが使われたかは実行のたびに変わりうるのに、誰も気づけない。
     *
     * @throws IllegalStateException 2 件以上見つかったとき
     */
    private static <T> Optional<T> only(List<T> found, String label, LocalDate date) {
        if (found.size() > 1) {
            throw new IllegalStateException(
                    "%s が %s 時点で %d 件あります: %s".formatted(label, date, found.size(), found));
        }
        return found.stream().findFirst();
    }
}
