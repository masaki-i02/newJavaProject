package jp.co.sample.kintai.attendance.presentation;

import java.time.LocalDateTime;
import java.util.Optional;

import jakarta.validation.constraints.NotNull;

import jp.co.sample.kintai.attendance.domain.TimeClockEvent;

/**
 * 打刻のリクエスト。
 *
 * <p><strong>{@code source} は受け取らない。</strong>
 * 画面からの打刻は必ず {@code WEB} になる。
 * 訂正申請の承認による追記（{@code CORRECTION}）は {@code approval} 経由でしか起きないので、
 * ここで指定できるようにすると訂正の証跡を偽装できてしまう。
 *
 * @param type       打刻種別
 * @param occurredAt 打刻時刻。<strong>秒まで受け付ける。</strong>
 *                   端末が秒まで送っても拒否しない。分へそろえるのはサーバの仕事（BR-01）。
 *                   省略時は {@code application} 層が {@code Clock} から解決する（AR-09）
 */
public record PunchRequest(@NotNull TimeClockEvent.Type type, LocalDateTime occurredAt) {

    Optional<LocalDateTime> occurredAtOrNow() {
        return Optional.ofNullable(occurredAt);
    }
}
