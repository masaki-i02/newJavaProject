package jp.co.sample.kintai.payroll.presentation;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jp.co.sample.kintai.employee.domain.Employee;
import jp.co.sample.kintai.payroll.domain.PayrollExport;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * 出力を作った結果（API設計書 2）。
 *
 * <p><strong>除外した社員は社員番号で返す。</strong>
 * 人事は「誰を締めればよいか」を直接読むので、UUID では行動できない。
 * 出力そのものが社員番号で名寄せするためのものであり、
 * この応答も同じ目的の一部である（API設計書 1.2）。
 *
 * @param excluded 除外した社員。<strong>空でも項目ごと省かない。</strong>
 *                 空配列は「全員が締まっている」という積極的な意味を持つ
 */
public record PayrollExportResponse(UUID exportId, YearMonth month,
                                    LocalDateTime exportedAt,
                                    int monthlyAverageMinutes,
                                    int rowCount,
                                    List<ExcludedEmployee> excluded) {

    /** 除外した社員 1 人。 */
    public record ExcludedEmployee(String employeeNumber, String reason) {
    }

    static PayrollExportResponse from(PayrollExport export, List<Employee> employees) {
        Map<EmployeeId, String> numbers = employees.stream()
                .collect(java.util.stream.Collectors.toMap(Employee::id,
                        employee -> employee.number().value()));
        List<ExcludedEmployee> excluded = export.targets().entrySet().stream()
                .filter(entry -> entry.getValue().isPresent())
                .map(entry -> new ExcludedEmployee(
                        numbers.getOrDefault(entry.getKey(), entry.getKey().value().toString()),
                        entry.getValue().orElseThrow().name()))
                // ★ 並び順は表示側で決める。永続化アダプタが employees を JOIN すると、
                //   ArchUnit が見ない SQL の中に他コンテキストへの辺が生まれる
                .sorted(java.util.Comparator.comparing(ExcludedEmployee::employeeNumber))
                .toList();
        return new PayrollExportResponse(export.id().value(), export.month(),
                export.exportedAt().orElse(null), export.monthlyAverageMinutes(),
                export.rowCount(), excluded);
    }
}
