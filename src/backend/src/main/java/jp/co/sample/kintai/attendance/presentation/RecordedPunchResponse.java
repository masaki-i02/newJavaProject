package jp.co.sample.kintai.attendance.presentation;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;

import jp.co.sample.kintai.attendance.domain.TimeClockEntry;

/**
 * 打刻の 1 件（BR-09）。<strong>取り消されたものも含む。</strong>
 *
 * <p><strong>識別子を返す。</strong>
 * 訂正申請の取消は打刻の識別子で対象を指すので、
 * 識別子を返す経路が無いと利用者は対象を選べず、
 * 実在しない識別子を送って外部キー違反にするしかなくなる（落とし穴 66）。
 *
 * <p><strong>取り消された打刻も返す。</strong>
 * 「元は何時だったか」を提示できることが BR-09 の目的である。
 *
 * <p>時刻は<strong>壁掛け時計</strong>のまま返す（{@code LocalDateTime}）。
 * オフセットを付けて返すと、受け取った側が変換して深夜帯の判定がずれる（落とし穴 1）。
 *
 * <p>{@code @JsonInclude} は<strong>項目ごとに</strong>付ける。
 * record 全体に付けると、値が無いこと自体が意味を持つ項目まで消える（落とし穴 76）。
 *
 * @param id         打刻の識別子。訂正申請の {@code targetEventId} に渡す
 * @param type       打刻の種別
 * @param occurredAt 打刻時刻
 * @param source     入力の経路。{@code WEB} / {@code CORRECTION}
 * @param reason     訂正で追記された打刻の理由
 * @param revoked    取り消されているか
 * @param revocation 取消の記録
 */
public record RecordedPunchResponse(String id, String type, LocalDateTime occurredAt,
                                    String source,
                                    @JsonInclude(JsonInclude.Include.NON_NULL)
                                    String reason,
                                    boolean revoked,
                                    @JsonInclude(JsonInclude.Include.NON_NULL)
                                    RevocationResponse revocation) {

    /** 取消の記録。<strong>誰がいつ何の理由で取り消したか。</strong> */
    public record RevocationResponse(String reason, String recordedBy,
                                     LocalDateTime recordedAt) {
    }

    static RecordedPunchResponse from(TimeClockEntry entry) {
        return new RecordedPunchResponse(entry.id().value().toString(),
                entry.event().type().name(), entry.event().occurredAt(),
                entry.source().name(), entry.reason().orElse(null),
                entry.isRevoked(),
                entry.revocation()
                        .map(revocation -> new RevocationResponse(revocation.reason(),
                                revocation.recordedBy().value().toString(),
                                revocation.recordedAt()))
                        .orElse(null));
    }
}
