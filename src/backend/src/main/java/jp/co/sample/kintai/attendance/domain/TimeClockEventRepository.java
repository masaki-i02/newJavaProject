package jp.co.sample.kintai.attendance.domain;

import java.time.LocalDate;

import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * 打刻のポート。<strong>追記のみ。</strong>
 *
 * <p>更新も削除も提供しない。打刻は労働時間の一次証拠であり、
 * 労務トラブル時に「元は何時だったか」を提示できる必要がある（BR-09）。
 * 訂正は取消打刻を追記することで表現する。
 */
public interface TimeClockEventRepository {

    /**
     * 打刻を追記する。
     *
     * @param recordedBy 記録した本人。代理打刻の証跡になる
     */
    void append(EmployeeId employeeId, LocalDate workDate, TimeClockEvent event,
                EmployeeId recordedBy);

    /**
     * その勤務日の有効な打刻列。<strong>取り消された打刻は含まない。</strong>
     *
     * <p>打刻が 1 件も無い日は空の列を返す。休日や欠勤で正常に起こりうるので例外にしない。
     */
    TimeClockSequence findByWorkDate(EmployeeId employeeId, LocalDate workDate);
}
