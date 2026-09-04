package jp.co.sample.kintai.approval.domain;

import java.util.List;
import java.util.Optional;

import jp.co.sample.kintai.employee.domain.Managership;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * BR-11 が定める承認者と、そこへ至った経路。
 *
 * <p><strong>経路を持つ。</strong>
 * 「なぜこの人が承認者なのか」は運用中に必ず問い合わせが来る。
 * 結論だけを返すと、そのたびに組織の履歴を人が辿ることになる。
 */
public record Approver(ApproverKind kind, Optional<EmployeeId> employeeId,
                       List<ResolutionStep> path) {

    public Approver {
        if (kind == null || employeeId == null || path == null) {
            throw new IllegalArgumentException("承認者の項目に null は許されません");
        }
        // ★ 「個人承認なのに承認者が空」「人事承認なのに承認者がいる」を作れなくする
        if (kind == ApproverKind.INDIVIDUAL && employeeId.isEmpty()) {
            throw new IllegalArgumentException("個人承認なのに承認者が空です");
        }
        if (kind != ApproverKind.INDIVIDUAL && employeeId.isPresent()) {
            throw new IllegalArgumentException("個人承認でないのに承認者が設定されています");
        }
        path = List.copyOf(path);
    }

    public static Approver of(Managership managership, List<ResolutionStep> path) {
        return new Approver(ApproverKind.INDIVIDUAL,
                Optional.of(managership.employeeId()), path);
    }

    /** 遡っても得られなかったので人事が承認する（BR-11 の 5）。 */
    public static Approver humanResources(List<ResolutionStep> path) {
        return new Approver(ApproverKind.HUMAN_RESOURCES, Optional.empty(), path);
    }

    /** 対象月に所属が無い。承認者を問う場面が無い。 */
    public static Approver none(List<ResolutionStep> path) {
        return new Approver(ApproverKind.NONE, Optional.empty(), path);
    }

    /** その社員が承認してよいか。 */
    public boolean isApprovedBy(EmployeeId candidate, boolean candidateIsHumanResources) {
        return switch (kind) {
            case INDIVIDUAL -> employeeId.map(candidate::equals).orElse(false);
            // BR-11 の 5。人事なら誰でもよい。特定の担当者を指名する仕組みは持たない
            case HUMAN_RESOURCES -> candidateIsHumanResources;
            case NONE -> false;
        };
    }
}
