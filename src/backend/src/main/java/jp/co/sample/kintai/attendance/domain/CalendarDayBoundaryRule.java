package jp.co.sample.kintai.attendance.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 暦日境界（0:00）で区間を分割する。<strong>属性は付けない。</strong>
 *
 * <p>労働時間の帰属先は勤務日だが（BR-03）、
 * <strong>法定休日労働の割増は暦日で判断する</strong>（昭 63.1.1 基発 1 号）。
 *
 * <p>土曜 22:00 出勤 → 日曜 06:00 退勤で、勤務日の区分を全区間に適用すると、
 * 日曜 0:00–6:00 の 35% が付かない。<strong>賃金の過少払いになる。</strong>
 */
public record CalendarDayBoundaryRule() implements AttendanceRule {

    @Override
    public List<WorkSlice> apply(List<WorkSlice> slices) {
        List<WorkSlice> result = new ArrayList<>();
        for (WorkSlice slice : slices) {
            result.addAll(splitAtMidnights(slice));
        }
        return List.copyOf(result);
    }

    private static List<WorkSlice> splitAtMidnights(WorkSlice slice) {
        List<WorkSlice> parts = new ArrayList<>();
        WorkSlice remaining = slice;
        // 区間が複数の暦日にまたがる限り、最初の 0:00 で切り続ける
        while (true) {
            var midnight = remaining.range().start().toLocalDate().plusDays(1).atStartOfDay();
            List<WorkSlice> split = remaining.splitAt(midnight);
            if (split.size() == 1) {
                parts.add(remaining);
                return List.copyOf(parts);
            }
            parts.add(split.get(0));
            remaining = split.get(1);
        }
    }
}
