package jp.co.sample.kintai.shared.probe;

import jp.co.sample.kintai.leave.domain.LeaveRequestStatus;

/**
 * AR-10 違反。{@code shared} が {@code leave} を知っている状態。
 *
 * <p>この禁止先が最も踏まれやすい。{@code shared.domain.PaidLeaveDays} は
 * {@code leave} が実装するポートなので、
 * <strong>引数にうっかり {@code leave} の型を取る</strong>変更が起きやすい。
 */
public class SharedReachesIntoLeave {

    public LeaveRequestStatus status() {
        return LeaveRequestStatus.SUBMITTED;
    }
}
