package jp.co.sample.kintai.leave.presentation;

import com.fasterxml.jackson.annotation.JsonInclude;

import jp.co.sample.kintai.leave.application.PaidLeaveSummary;
import jp.co.sample.kintai.leave.domain.AttendanceRate;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrant;

/**
 * 付与の 1 件（BR-14 / BR-15）。
 *
 * <p><strong>不付与の年も返す。</strong> 行が無いのではなく「付与しなかった」ので、
 * 出勤率とともに残す。そのぶん {@code days} / {@code usedDays} /
 * {@code remainingDays} は意味を持たないので<strong>項目ごと省く</strong>。
 * 0 を返すと「0 日付与された」と読めてしまう。
 *
 * <p><strong>{@code @JsonInclude} を record 全体に付けない。</strong>
 * 付けると出勤率の内訳（{@code deemedReason} の {@code null}）まで消え、
 * 「申告が無い」ことを応答から読み取れなくなる（落とし穴 76）。
 *
 * @param expiresOn 失効日。<strong>半開区間の上限なので、この日には既に失効している</strong>
 */
record GrantResponse(String grantedOn, String expiresOn, boolean granted,
                     @JsonInclude(JsonInclude.Include.NON_NULL) Integer days,
                     @JsonInclude(JsonInclude.Include.NON_NULL) Integer usedDays,
                     @JsonInclude(JsonInclude.Include.NON_NULL) Integer remainingDays,
                     AttendanceRateResponse attendanceRate) {

    static GrantResponse from(PaidLeaveGrant grant, PaidLeaveSummary summary) {
        boolean granted = grant.isGranted();
        Integer remaining = granted ? summary.remainingOf(grant.id()) : null;
        Integer used = granted ? grant.days() - summary.remainingOf(grant.id()) : null;
        return new GrantResponse(grant.grantedOn().toString(),
                grant.validPeriod().toExclusive().toString(), granted,
                granted ? grant.days() : null, used, remaining,
                AttendanceRateResponse.from(grant.rate()));
    }

    /** 出勤率の内訳。<strong>判定の根拠として残す。</strong> */
    record AttendanceRateResponse(int totalWorkingDays, int attendedDays,
                                  int deemedAttendedDays, String deemedReason) {

        static AttendanceRateResponse from(AttendanceRate rate) {
            return new AttendanceRateResponse(rate.totalWorkingDays(),
                    rate.attendedDays(), rate.deemedAttendedDays(), rate.deemedReason());
        }
    }
}
