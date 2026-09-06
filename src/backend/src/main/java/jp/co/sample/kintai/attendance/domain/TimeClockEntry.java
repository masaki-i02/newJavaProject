package jp.co.sample.kintai.attendance.domain;

import java.time.LocalDateTime;
import java.util.Optional;

import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * 打刻の 1 行。<strong>取り消されたものも含む</strong>（BR-09）。
 *
 * <p>{@link RecordedTimeClockEvent} は「いま有効な打刻」だけを表す。
 * こちらは<strong>証跡</strong>であり、
 * 「元は何時だったか」「誰がいつ何の理由で取り消したか」を提示するために使う。
 * それを示せることが BR-09 の目的である。
 *
 * <p>打刻そのものは値であって識別子を持たない（落とし穴 65）ので、
 * 識別子と証跡はこの型が外から添える。
 *
 * @param id         打刻の識別子。訂正申請の {@code targetEventId} に渡す
 * @param event      打刻そのもの
 * @param source     入力の経路。{@code WEB} か、訂正の承認による {@code CORRECTION}
 * @param reason     訂正で追記された打刻の理由。通常の打刻では空
 * @param revocation 取り消されていればその記録。有効な打刻では空
 */
public record TimeClockEntry(TimeClockEventId id, TimeClockEvent event,
                             Source source, Optional<String> reason,
                             Optional<Revocation> revocation) {

    /** 入力の経路。<strong>要件に無い打刻手段を増やさない。</strong> */
    public enum Source {
        WEB, CORRECTION
    }

    /**
     * 取消の記録。
     *
     * @param reason     理由。<strong>必須</strong>（DB の CHECK でも守っている）
     * @param recordedBy 取り消した人。訂正の承認者である
     * @param recordedAt 取り消した日時
     */
    public record Revocation(String reason, EmployeeId recordedBy,
                             LocalDateTime recordedAt) {

        public Revocation {
            if (reason == null || reason.isBlank() || recordedBy == null
                    || recordedAt == null) {
                throw new IllegalArgumentException("取消の記録には理由と実行者が要ります");
            }
        }
    }

    public TimeClockEntry {
        if (id == null || event == null || source == null || reason == null
                || revocation == null) {
            throw new IllegalArgumentException("打刻の証跡の項目に null は許されません");
        }
        // ★ 訂正で追記された打刻には理由がある（DB の CHECK と同じ不変条件）
        if (source == Source.CORRECTION && reason.isEmpty()) {
            throw new IllegalArgumentException("訂正で追記した打刻には理由が要ります");
        }
    }

    /** 取り消されているか。 */
    public boolean isRevoked() {
        return revocation.isPresent();
    }
}
