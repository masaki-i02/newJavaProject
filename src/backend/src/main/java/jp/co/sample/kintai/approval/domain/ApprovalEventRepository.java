package jp.co.sample.kintai.approval.domain;

import java.util.List;

/**
 * 監査証跡のポート。<strong>追記のみ。</strong>
 *
 * <p>更新も削除も提供しない。証跡を後から書き換えられると証跡ではなくなる。
 */
public interface ApprovalEventRepository {

    void append(ApprovalEvent event);

    /** その月次勤怠の証跡。発生順。 */
    List<ApprovalEvent> findBy(MonthlyAttendanceId monthlyAttendanceId);
}
