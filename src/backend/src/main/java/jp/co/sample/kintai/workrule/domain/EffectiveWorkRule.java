package jp.co.sample.kintai.workrule.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 指定日に社員へ適用される就業規則を決める（時点解決）。
 *
 * <p>解決は 2 段階である。
 * <pre>
 * employeeId + date ──> 適用（assignment）──> 系列
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
     * @param assignments その社員の適用の履歴
     * @param versions    その系列の版の履歴
     * @param date        対象日
     * @return 適用が無い、または版に隙間がある場合は空。<strong>例外にしない</strong>
     */
    public static Optional<WorkRule> resolve(List<WorkRuleAssignment> assignments,
                                             List<WorkRule> versions, LocalDate date) {
        if (assignments == null || versions == null || date == null) {
            throw new IllegalArgumentException("時点解決の引数に null は許されません");
        }
        return only(assignments.stream()
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
     * ただ 1 件であることを確かめて返す。0 件なら空。
     *
     * <p><strong>findFirst にしない。</strong>
     * 適用も版も期間が重ならないことを DB の {@code EXCLUDE} 制約で保証しているので、
     * 2 件見つかるのは制約が壊れたか、呼び出し側が別の社員・別の系列の履歴を
     * 混ぜて渡したかのどちらかである。先頭を黙って採ると、
     * どちらが使われたかは実行のたびに変わりうるのに、誰も気づけない。
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
