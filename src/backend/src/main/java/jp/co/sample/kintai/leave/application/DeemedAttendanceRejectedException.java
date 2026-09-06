package jp.co.sample.kintai.leave.application;

import java.io.Serial;

import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;

/**
 * 人事が申告した「出勤扱いの日数」を受け付けられない（BR-14）。
 *
 * <p><strong>実装の不備として扱わない。</strong>
 * {@code deemedAttendedDays} / {@code deemedReason} は人事が画面から送る値であり、
 * {@code IllegalArgumentException} のまま素通しすると
 * {@link jp.co.sample.kintai.shared.domain.DomainException} ではないので
 * <strong>理由の載らない 500</strong> になる。何日なら通るのかが人事に伝わらない
 * （API設計書 4.2 は 422 を求めている）。
 */
public final class DeemedAttendanceRejectedException extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    DeemedAttendanceRejectedException(String detail) {
        super(detail);
    }

    /** 出勤日 + 出勤扱いが全労働日を超える。 */
    static DeemedAttendanceRejectedException exceedsTotal(int attended, int deemed,
                                                          int total) {
        return new DeemedAttendanceRejectedException(
                "出勤日と出勤扱いの和が全労働日を超えています: %d + %d / %d"
                        .formatted(attended, deemed, total));
    }

    /** 出勤扱いを申告したのに理由が無い。 */
    static DeemedAttendanceRejectedException reasonMissing() {
        return new DeemedAttendanceRejectedException(
                "出勤扱いの日数を申告するときは理由が必要です");
    }

    /** 日数が負。 */
    static DeemedAttendanceRejectedException negative(int deemed) {
        return new DeemedAttendanceRejectedException(
                "出勤扱いの日数が負です: " + deemed);
    }

    @Override
    public String errorCode() {
        return "urn:kintai:error:deemed-attendance-rejected";
    }

    @Override
    public DomainErrorKind kind() {
        return DomainErrorKind.RULE_VIOLATION;
    }

    @Override
    public String title() {
        return "出勤扱いの申告を受け付けられません";
    }
}
