package jp.co.sample.kintai.attendance.domain;

import java.util.ArrayList;
import java.util.List;

import jp.co.sample.kintai.shared.domain.PremiumType;
import jp.co.sample.kintai.shared.domain.TimeRange;
import jp.co.sample.kintai.workrule.domain.NightWindow;

/**
 * 深夜帯の境界で分割し、{@link PremiumType#NIGHT} を付与する（BR-06）。
 *
 * <p><strong>深夜は他の区分に重ねて付く属性であり、労働時間を分割する区分ではない。</strong>
 * この区別を持たないと「深夜だけが付いた基本時間の区間」をどの区分にも数え損ね、
 * 内訳の合計が実労働時間と一致しなくなる。
 */
public record NightWorkRule(NightWindow nightWindow) implements AttendanceRule {

    public NightWorkRule {
        if (nightWindow == null) {
            throw new IllegalArgumentException("深夜帯に null は許されません");
        }
    }

    @Override
    public List<WorkSlice> apply(List<WorkSlice> slices) {
        List<WorkSlice> result = new ArrayList<>();
        for (WorkSlice slice : slices) {
            result.addAll(applyTo(slice));
        }
        return List.copyOf(result);
    }

    private List<WorkSlice> applyTo(WorkSlice slice) {
        List<TimeRange> nights = nightWindow.intervalsOverlapping(slice.range());
        if (nights.isEmpty()) {
            return List.of(slice);
        }
        List<WorkSlice> parts = new ArrayList<>(List.of(slice));
        // 重なる深夜帯の端点で切る。切った結果に対して属性を付ける
        for (TimeRange night : nights) {
            parts = splitAll(parts, night);
        }
        return parts.stream()
                .map(part -> insideNight(nights, part) ? part.with(PremiumType.NIGHT) : part)
                .toList();
    }

    private static List<WorkSlice> splitAll(List<WorkSlice> parts, TimeRange night) {
        List<WorkSlice> split = new ArrayList<>();
        for (WorkSlice part : parts) {
            List<WorkSlice> byStart = part.splitAt(night.start());
            for (WorkSlice piece : byStart) {
                split.addAll(piece.splitAt(night.end()));
            }
        }
        return split;
    }

    /**
     * 分割後の区間が深夜帯の内側にあるか。
     *
     * <p>深夜帯の端点ですべて切ったあとなので、区間は深夜帯に
     * <strong>完全に含まれるか、まったく重ならないか</strong>のどちらかしかない。
     * よって開始時刻が深夜帯に入っているかだけを見ればよい。
     * 区間の一致で判定すると、他の規則がさらに細かく切った断片を取りこぼす。
     */
    private static boolean insideNight(List<TimeRange> nights, WorkSlice slice) {
        return nights.stream().anyMatch(night -> night.contains(slice.range().start()));
    }
}
