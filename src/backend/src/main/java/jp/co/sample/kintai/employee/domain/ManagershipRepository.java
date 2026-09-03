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

    void save(Managership managership);

    void close(DepartmentId departmentId, LocalDate toExclusive);
}
