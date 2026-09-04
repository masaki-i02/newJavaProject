package jp.co.sample.kintai.employee.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jp.co.sample.kintai.shared.domain.EmployeeId;

/** 所属のポート。 */
public interface AssignmentRepository {

    /**
     * 指定日に有効な所属。
     *
     * <p><strong>{@code Optional} で足りるのは、有効期間の重複を DB の排他制約が
     * 禁止しているからである。</strong>「複数見つかったらどうするか」を
     * アプリケーションで考えなくてよい。
     */
    Optional<Assignment> findEffective(EmployeeId employeeId, LocalDate date);

    /** その社員の所属の履歴。開始日の昇順。 */
    List<Assignment> findHistory(EmployeeId employeeId);

    void save(Assignment assignment);

    /** 現在開いている期間を指定日で閉じる。異動・退職で使う。 */
    void close(EmployeeId employeeId, LocalDate toExclusive);

    /**
     * 指定日で閉じた期間を開き直す。<strong>退職の取消でだけ使う。</strong>
     *
     * <p>閉じた日を指定して戻すので、
     * 退職とは無関係に閉じた過去の所属（異動）を巻き戻すことはない。
     *
     * @return 開き直した件数
     */
    int reopenClosedAt(EmployeeId employeeId, LocalDate toExclusive);
}
