package jp.co.sample.kintai.employee.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jp.co.sample.kintai.shared.domain.EmployeeId;

/** 部署長のポート。 */
public interface ManagershipRepository {

    /**
     * 指定日にその部署の長を務めていた社員。
     *
     * <p><strong>{@code EmployeeId} ではなく {@link Managership} を返す。</strong>
     * 呼び出し側が「いつから就任している長か」を必要とする（API の承認者の経路に載せる）。
     */
    Optional<Managership> findEffective(DepartmentId departmentId, LocalDate date);

    /** その社員が指定日に長を務めている部署。兼任があるので複数返りうる。 */
    List<Managership> findByManager(EmployeeId employeeId, LocalDate date);

    /**
     * その部署の就任の履歴。
     *
     * <p>任命は「現任を閉じて新しい期間を開く」操作なので、
     * <strong>指定日より後に始まる就任が既にあると期間が重なる。</strong>
     * DB の排他制約でも弾かれるが、制約違反は利用者に説明できない（落とし穴 66）。
     */
    List<Managership> findHistory(DepartmentId departmentId);

    void save(Managership managership);

    void close(DepartmentId departmentId, LocalDate toExclusive);

    /**
     * 部署長を交代させる。
     * <strong>現在の部署長を閉じてから新しい部署長を開くまでを 1 操作にする。</strong>
     *
     * <p>理由は {@code AssignmentRepository#transfer} と同じ。
     * {@code managerships_no_overlap} も DEFERRABLE ではない。
     */
    void appoint(Managership next);

    /**
     * その社員が務めている部署長の期間を、すべて指定日で閉じる。
     *
     * <p><strong>退職で使う。部署ごとではなく社員ごとに閉じる。</strong>
     * 閉じ忘れると、<strong>その部署に所属する全社員の承認者が退職者になり続ける。</strong>
     * 既存の行が後から不正になる種類の問題なので、DB の制約では検出できない。
     *
     * <p>部署長の兼任は認めているので、複数件になりうる。
     *
     * @return 閉じた件数
     */
    int closeByManager(EmployeeId employeeId, LocalDate toExclusive);

    /**
     * 指定日で閉じた部署長の期間を開き直す。<strong>退職の取消でだけ使う。</strong>
     *
     * @return 開き直した件数
     */
    int reopenClosedAt(EmployeeId employeeId, LocalDate toExclusive);
}
