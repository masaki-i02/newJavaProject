package jp.co.sample.kintai.leave.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jp.co.sample.kintai.shared.domain.EmployeeId;

/** 付与のポート。 */
public interface PaidLeaveGrantRepository {

    void save(PaidLeaveGrant grant);

    /** 再判定で上書きする。版が一致しなければ楽観ロック違反。 */
    void update(PaidLeaveGrant grant, long expectedVersion);

    Optional<PaidLeaveGrant> find(EmployeeId employeeId, LocalDate grantedOn);

    /**
     * その社員の付与を古い順に読む。
     *
     * <p><strong>失効しているかどうかで絞らない。</strong>
     * 判定は {@link PaidLeaveGrant#validPeriod()} が行う。
     * SQL に写すと、うるう年の境界で 1 日ずれる（落とし穴 91）。
     */
    List<PaidLeaveGrant> findAll(EmployeeId employeeId);

    /** 付与処理の対象。基準日までに付与日が到来している社員を返す。 */
    List<PaidLeaveGrant> findGrantedFor(List<EmployeeId> employeeIds);
}
