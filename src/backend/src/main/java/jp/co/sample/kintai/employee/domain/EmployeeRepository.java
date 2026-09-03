package jp.co.sample.kintai.employee.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jp.co.sample.kintai.shared.domain.EmployeeId;

/** 社員のポート。実装は {@code infrastructure}。 */
public interface EmployeeRepository {

    Optional<Employee> findById(EmployeeId id);

    /** 社員番号は認証 ID を兼ねるので、ログインでも使う。 */
    Optional<Employee> findByNumber(EmployeeNumber number);

    /**
     * 指定日時点の社員一覧。
     *
     * @param includeRetired 退職者を含めるか。過去分の勤怠を扱うときは含める必要がある
     */
    List<Employee> findAll(LocalDate asOf, boolean includeRetired);

    void save(Employee employee);
}
