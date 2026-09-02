package jp.co.sample.kintai.workrule.domain;

import java.time.Duration;
import java.time.YearMonth;

/**
 * 清算期間の所定総労働時間が、法定労働時間の総枠を超えている（BR-05）。
 *
 * <p><strong>違法ではない。</strong> 所定どおり働くだけで法定外残業が発生する状態というだけで、
 * 36 協定と割増賃金があれば適法である。月次清算の
 * {@code overtime = max(0, 対象労働時間 − 法定総枠)} が残業として扱うので、
 * 賃金の取りこぼしも起きない。
 *
 * <p>したがって<strong>例外にしない。</strong> 登録は許し、人事に知らせるだけにとどめる。
 * 例外にすると、適法な設定を保存できなくなる（CLAUDE.md 落とし穴 23）。
 *
 * @param month     対象月
 * @param scheduled 所定総労働時間
 * @param limit     法定労働時間の総枠
 */
public record ScheduleExceedsStatutoryLimit(YearMonth month, Duration scheduled, Duration limit) {

    public ScheduleExceedsStatutoryLimit {
        if (month == null || scheduled == null || limit == null) {
            throw new IllegalArgumentException("警告の項目に null は許されません");
        }
        if (scheduled.compareTo(limit) <= 0) {
            throw new IllegalArgumentException(
                    "総枠を超えていないのに警告を作ろうとしています: 所定総 %s / 総枠 %s"
                            .formatted(scheduled, limit));
        }
    }

    /** 超過分。 */
    public Duration excess() {
        return scheduled.minus(limit);
    }
}
