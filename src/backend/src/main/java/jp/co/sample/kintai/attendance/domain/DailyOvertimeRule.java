package jp.co.sample.kintai.attendance.domain;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import jp.co.sample.kintai.shared.domain.PremiumType;

/**
 * 累積労働時間の境界で分割し、残業の区分を付与する（BR-04）。
 *
 * <pre>
 * [0, 所定)      属性なし（基本時間）
 * [所定, 法定)   OVERTIME_WITHIN_STATUTORY  法定内残業。割増の支払義務なし
 * [法定, ∞)      OVERTIME_BEYOND_STATUTORY  法定外残業。25% 以上
 * </pre>
 *
 * <p><strong>累積は法定休日労働の区間を数えない。</strong>
 * 法定休日労働は時間外労働に算入しないため（労基法 36 条）。
 * 数えてしまうと、土曜深夜から日曜にかけて働いた社員の日曜分が
 * 時間外にも法定休日にも二重に計上される。
 *
 * <p><strong>フレックスタイム制ではこの規則を適用しない。</strong>
 * 日々 8 時間を超えても、それ自体では時間外労働にならない（BR-05）。
 *
 * @param scheduled 所定労働時間。<strong>勤務日の区分で決まる</strong>（所定休日・法定休日なら 0）
 * @param statutory 1 日の法定労働時間。原則 8 時間
 */
public record DailyOvertimeRule(Duration scheduled, Duration statutory)
        implements AttendanceRule {

    public DailyOvertimeRule {
        if (scheduled == null || statutory == null) {
            throw new IllegalArgumentException("残業判定の基準に null は許されません");
        }
        if (scheduled.isNegative() || statutory.isNegative()) {
            throw new IllegalArgumentException("残業判定の基準を負にはできません");
        }
        if (scheduled.compareTo(statutory) > 0) {
            throw new IllegalArgumentException(
                    "所定労働時間が法定労働時間を超えています: %s > %s".formatted(scheduled, statutory));
        }
    }

    @Override
    public List<WorkSlice> apply(List<WorkSlice> slices) {
        List<WorkSlice> result = new ArrayList<>();
        Duration accumulated = Duration.ZERO;

        for (WorkSlice slice : slices) {
            // 法定休日労働は累積に数えず、区分も付けない
            if (slice.has(PremiumType.LEGAL_HOLIDAY)) {
                result.add(slice);
                continue;
            }
            for (WorkSlice part : splitAtThresholds(slice, accumulated)) {
                result.add(classify(part, accumulated));
                accumulated = accumulated.plus(part.duration());
            }
        }
        return List.copyOf(result);
    }

    /** 累積が所定・法定の境界をまたぐ区間を、その点で切る。 */
    private List<WorkSlice> splitAtThresholds(WorkSlice slice, Duration accumulated) {
        List<WorkSlice> parts = new ArrayList<>(List.of(slice));
        for (Duration threshold : List.of(scheduled, statutory)) {
            Duration offset = threshold.minus(accumulated);
            if (offset.isNegative() || offset.isZero()) {
                continue;   // 既に越えている境界では切らない
            }
            List<WorkSlice> split = new ArrayList<>();
            Duration position = Duration.ZERO;
            for (WorkSlice part : parts) {
                Duration end = position.plus(part.duration());
                if (offset.compareTo(position) > 0 && offset.compareTo(end) < 0) {
                    split.addAll(part.splitAt(part.range().start().plus(offset.minus(position))));
                } else {
                    split.add(part);
                }
                position = end;
            }
            parts = split;
        }
        return parts;
    }

    /** 累積した位置で、その区間がどの区分に入るかを決める。 */
    private WorkSlice classify(WorkSlice slice, Duration accumulated) {
        if (accumulated.compareTo(statutory) >= 0) {
            return slice.with(PremiumType.OVERTIME_BEYOND_STATUTORY);
        }
        if (accumulated.compareTo(scheduled) >= 0) {
            return slice.with(PremiumType.OVERTIME_WITHIN_STATUTORY);
        }
        return slice;   // 基本時間。属性は付けない
    }
}
