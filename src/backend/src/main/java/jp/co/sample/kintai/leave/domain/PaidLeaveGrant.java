package jp.co.sample.kintai.leave.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * 1 回の付与（BR-14 / BR-15）。
 *
 * <p><strong>参照時に導出せず、行として実体化する。</strong>
 * 実績が訂正されると過去の付与日数が黙って変わり、
 * 消化済みの日数を下回ることもありうる（ADR 0006）。
 *
 * @param id         識別子
 * @param employeeId 社員
 * @param grantIndex 何回目の付与か（0 起点）。0 = 入社 6 か月後
 * @param grantedOn  付与日
 * @param rate       出勤率。<strong>判定の根拠として残す</strong>
 * @param decision   付与したかどうか
 * @param assessedAt 判定した日時
 * @param version    楽観ロックの版
 */
public record PaidLeaveGrant(PaidLeaveGrantId id, EmployeeId employeeId, int grantIndex,
                             LocalDate grantedOn, AttendanceRate rate,
                             GrantDecision decision, LocalDateTime assessedAt,
                             long version) {

    /** 時効（労基法 115 条・BR-15）。 */
    private static final int VALID_YEARS = 2;

    public PaidLeaveGrant {
        if (id == null || employeeId == null || grantedOn == null || rate == null
                || decision == null || assessedAt == null) {
            throw new IllegalArgumentException("付与の項目に null は許されません");
        }
        if (grantIndex < 0) {
            throw new IllegalArgumentException("付与の連番が負です: " + grantIndex);
        }
    }

    /**
     * 有効期間。付与日から 2 年（BR-15）。<strong>半開区間</strong>。
     *
     * <p><strong>この判定を SQL に写さない。</strong>
     * {@code granted_on + 2 年 > asOf} を移項した {@code granted_on > asOf - 2 年} は、
     * 日付演算のクランプがあるため等価にならない。
     * 2024-02-29 に付与した年休は、ドメインでは 2026-02-28 に失効するが、
     * 移項した SQL では同じ日がまだ有効になる（落とし穴 91）。
     */
    public DateRange validPeriod() {
        return new DateRange(grantedOn, grantedOn.plusYears(VALID_YEARS));
    }

    /** その日に有効か。付与日当日は有効、失効日当日は無効。 */
    public boolean isValidOn(LocalDate date) {
        return isGranted() && validPeriod().contains(date);
    }

    public boolean isGranted() {
        return decision instanceof GrantDecision.Granted;
    }

    /** 付与日数。不付与なら 0。 */
    public int days() {
        return switch (decision) {
            case GrantDecision.Granted granted -> granted.days();
            case GrantDecision.Withheld ignored -> 0;
        };
    }

    /**
     * 現在の実績で判定し直す（BR-14）。
     *
     * <p><strong>付与を取り消す経路は設けない。</strong>
     * 一度発生した年休の権利を実績の訂正で消すのは労働者に不利であり、
     * 消化済みなら辻褄も合わなくなる。
     *
     * @throws AlreadyGrantedException 付与済みの付与を再判定しようとした
     */
    public PaidLeaveGrant reassess(AttendanceRate newRate, LocalDateTime at) {
        if (isGranted()) {
            throw new AlreadyGrantedException(grantedOn);
        }
        GrantDecision next = newRate.meetsThreshold()
                ? new GrantDecision.Granted(LeaveEntitlement.of(grantIndex).days())
                : new GrantDecision.Withheld();
        return new PaidLeaveGrant(id, employeeId, grantIndex, grantedOn, newRate, next,
                at, version);
    }
}
