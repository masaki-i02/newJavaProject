package jp.co.sample.kintai.attendance.domain;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import jp.co.sample.kintai.shared.domain.PremiumType;
import jp.co.sample.kintai.shared.domain.TimeRange;

/**
 * 割増属性が確定した労働区間の最小単位。
 *
 * <p>勤怠計算は「1 本の労働区間を、割増の切り替わり点でひたすら細切れにしていく」処理として
 * 表現できる。この型がその細切れ 1 つを表す。
 *
 * @param range    区間。半開区間・分精度
 * @param premiums 付与された割増区分。深夜は他に重ねて付く
 */
public record WorkSlice(TimeRange range, Set<PremiumType> premiums) {

    public WorkSlice {
        if (range == null || premiums == null) {
            throw new IllegalArgumentException("労働区間の項目に null は許されません");
        }
        // EnumSet は反復順が宣言順で安定し、contains が速い。
        // 不変にするラップは 1 回で足りる（Set.copyOf を重ねると無駄にもう 1 つ作る）
        premiums = premiums.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(premiums));
        long exclusive = premiums.stream().filter(PremiumType::partitionsWorkingTime).count();
        if (exclusive > 1) {
            throw new IllegalArgumentException(
                    "排他的な割増区分が 1 区間に 2 つ以上付いています: " + premiums);
        }
    }

    /** 属性の無い区間。 */
    public static WorkSlice plain(TimeRange range) {
        return new WorkSlice(range, Set.of());
    }

    public Duration duration() {
        return range.duration();
    }

    /** この区間が属する暦日。区間は暦日境界で分割されるので一意に決まる。 */
    public LocalDate calendarDate() {
        return range.start().toLocalDate();
    }

    public boolean has(PremiumType premium) {
        return premiums.contains(premium);
    }

    /** 割増を 1 つ足した区間を返す。元の区間は変えない。 */
    public WorkSlice with(PremiumType premium) {
        if (premiums.contains(premium)) {
            return this;
        }
        Set<PremiumType> added = EnumSet.noneOf(PremiumType.class);
        added.addAll(premiums);
        added.add(premium);
        return new WorkSlice(range, added);
    }

    /** 指定の時刻で 2 つに分ける。属性は引き継ぐ。端点なら分けない。 */
    public List<WorkSlice> splitAt(LocalDateTime instant) {
        return range.splitAt(instant).stream()
                .map(part -> new WorkSlice(part, premiums))
                .toList();
    }
}
