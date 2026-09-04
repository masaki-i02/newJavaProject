package jp.co.sample.kintai.approval.domain;

import java.time.LocalDateTime;

import jp.co.sample.kintai.attendance.domain.TimeClockEvent;
import jp.co.sample.kintai.attendance.domain.TimeClockEventId;

/**
 * 訂正の 1 項目（BR-09）。
 *
 * <p><strong>「変更」という操作を用意しない。</strong>
 * 取消と追加の組み合わせで表現する。
 * 変更を許すと元の打刻の値が失われ、BR-09 の目的（一次証拠の保全）を満たせない。
 */
public sealed interface CorrectionItem {

    /**
     * 既存の打刻を取り消す。
     *
     * <p>勤務日は申請が持つので、ここには対象の識別子だけを持たせる。
     */
    record Revoke(TimeClockEventId targetId) implements CorrectionItem {

        public Revoke {
            if (targetId == null) {
                throw new IllegalArgumentException("取り消す対象が必要です");
            }
        }
    }

    /**
     * 新しい打刻を追加する。
     *
     * <p><strong>打刻漏れの補完もこれで行う。</strong> 取り消す対象が存在しないため。
     */
    record Add(TimeClockEvent event) implements CorrectionItem {

        public Add {
            if (event == null) {
                throw new IllegalArgumentException("追加する打刻が必要です");
            }
        }

        public LocalDateTime occurredAt() {
            return event.occurredAt();
        }
    }
}
