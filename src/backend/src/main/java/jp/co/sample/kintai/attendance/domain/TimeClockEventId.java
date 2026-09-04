package jp.co.sample.kintai.attendance.domain;

import java.util.UUID;

/**
 * 打刻の識別子。
 *
 * <p><strong>取消の対象を指すために要る。</strong>
 * 打刻そのもの（{@link TimeClockEvent}）は識別子を持たない。
 * 労働時間の計算に必要なのは種別と時刻だけであり、
 * 識別子を持たせると値としての等価性（同じ時刻の同じ種別は同じもの）が壊れるからである。
 *
 * <p>識別子が要るのは訂正だけなので、{@link RecordedTimeClockEvent} で外から添える。
 */
public record TimeClockEventId(UUID value) {

    public TimeClockEventId {
        if (value == null) {
            throw new IllegalArgumentException("打刻の識別子に null は許されません");
        }
    }
}
