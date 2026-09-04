package jp.co.sample.kintai.leave.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 残日数と先入先出の配分（BR-15）。
 *
 * <p><strong>残日数を列で持たない。</strong> 付与と配分から導く。
 * 列にすると付与・取得・取下げ・時効の 4 か所で更新することになり、
 * 1 か所落とすと静かにずれる。残数の整合は他の表の集計に依存するので
 * {@code CHECK} では守れない（ADR 0006）。
 *
 * <p><strong>時効も行にしない。</strong> 失効は「付与日 + 2 年」だけで決まる純粋な関数であり、
 * バッチで行を作ると実行し忘れた日に残日数が過大になる。
 *
 * @param grants    付与。{@link PaidLeaveGrant#isGranted()} でないものを含んでよい
 * @param allocated 承認済みの配分
 */
public record PaidLeaveBalance(List<PaidLeaveGrant> grants, List<LeaveAllocation> allocated) {

    public PaidLeaveBalance {
        if (grants == null || allocated == null) {
            throw new IllegalArgumentException("残日数の項目に null は許されません");
        }
        grants = List.copyOf(grants);
        allocated = List.copyOf(allocated);
    }

    /**
     * その日を消化できる付与を選ぶ。無ければ空。
     *
     * <p><strong>取得日の時点で有効な付与のうち、最も古いもの</strong>（先入先出・BR-15）。
     * 新しい付与から消化すると、古い分が失効しやすくなり労働者に不利になる。
     *
     * <p>「最も古い」を付与日で決める。失効日（付与日 + 2 年）で決めても同じ順序になるが、
     * 付与日のほうが行に存在する値であり、同じ規則を 2 つ持たない（落とし穴 77）。
     */
    public Optional<PaidLeaveGrantId> allocationFor(LocalDate leaveDate) {
        return allocationFor(leaveDate, remainingByGrant());
    }

    /**
     * 基準日に有効な付与の残の合計。
     *
     * <p>実体化した付与だけを数える。<strong>基準日に実際に保有している日数</strong>である。
     */
    public int remainingDays(LocalDate asOf) {
        Map<PaidLeaveGrantId, Integer> remaining = remainingByGrant();
        return grants.stream()
                .filter(grant -> grant.isValidOn(asOf))
                .mapToInt(grant -> remaining.getOrDefault(grant.id(), 0))
                .sum();
    }

    /**
     * 未処理の申請を仮に配分したあと、なお残っている日数。
     *
     * <p><strong>件数の引き算にしない。</strong>
     * 「合計では足りているが、その日に有効な付与だけでは足りない」場合に実際と食い違う。
     * 表示に出る値と、申請の受理判定が別の式になってはならない（落とし穴 96）。
     *
     * @param pendingLeaveDates 未処理の申請の取得日
     */
    public int availableDays(LocalDate asOf, List<LocalDate> pendingLeaveDates) {
        Map<PaidLeaveGrantId, Integer> remaining = simulate(pendingLeaveDates);
        return grants.stream()
                .filter(grant -> grant.isValidOn(asOf))
                .mapToInt(grant -> remaining.getOrDefault(grant.id(), 0))
                .sum();
    }

    /**
     * 未処理の申請をすべて配分したうえで、さらにその日を配分できるか。
     *
     * <p>承認済みだけを引くと、残 1 日に対して 2 件の申請が同時に通る。
     */
    public boolean canAllocate(LocalDate leaveDate, List<LocalDate> pendingLeaveDates) {
        return allocationFor(leaveDate, simulate(pendingLeaveDates)).isPresent();
    }

    /** 付与ごとの残日数。 */
    public Map<PaidLeaveGrantId, Integer> remainingByGrant() {
        Map<PaidLeaveGrantId, Integer> remaining = new HashMap<>();
        for (PaidLeaveGrant grant : grants) {
            if (grant.isGranted()) {
                remaining.put(grant.id(), grant.days());
            }
        }
        for (LeaveAllocation allocation : allocated) {
            remaining.computeIfPresent(allocation.grantId(), (id, days) -> days - 1);
        }
        return remaining;
    }

    /**
     * 未処理の申請を取得日の古い順に仮配分した結果。
     *
     * <p><strong>取得日の順に配分する。</strong> 申請の順ではない。
     * 先入先出はどの付与が有効かで決まり、それは取得日に依存する。
     */
    private Map<PaidLeaveGrantId, Integer> simulate(List<LocalDate> pendingLeaveDates) {
        Map<PaidLeaveGrantId, Integer> remaining = remainingByGrant();
        List<LocalDate> ordered = new ArrayList<>(pendingLeaveDates);
        ordered.sort(Comparator.naturalOrder());
        for (LocalDate date : ordered) {
            allocationFor(date, remaining)
                    .ifPresent(id -> remaining.computeIfPresent(id, (key, days) -> days - 1));
        }
        return remaining;
    }

    private Optional<PaidLeaveGrantId> allocationFor(LocalDate leaveDate,
                                                     Map<PaidLeaveGrantId, Integer> remaining) {
        if (leaveDate == null) {
            throw new IllegalArgumentException("取得日に null は許されません");
        }
        return grants.stream()
                .filter(grant -> grant.isValidOn(leaveDate))
                .filter(grant -> remaining.getOrDefault(grant.id(), 0) > 0)
                .min(Comparator.comparing(PaidLeaveGrant::grantedOn))
                .map(PaidLeaveGrant::id);
    }
}
