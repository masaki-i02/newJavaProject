package jp.co.sample.kintai.payroll.application;

import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sample.kintai.approval.application.MonthlyAttendanceService;
import jp.co.sample.kintai.approval.domain.AttendanceState;
import jp.co.sample.kintai.attendance.application.MonthlySettlementService;
import jp.co.sample.kintai.attendance.domain.monthly.MonthlyDayCounts;
import jp.co.sample.kintai.attendance.domain.monthly.MonthlySettlement;
import jp.co.sample.kintai.employee.application.EmployeeDirectoryService;
import jp.co.sample.kintai.employee.domain.Employee;
import jp.co.sample.kintai.employee.domain.EmployeeNumber;
import jp.co.sample.kintai.payroll.domain.ExclusionReason;
import jp.co.sample.kintai.payroll.domain.PayrollExport;
import jp.co.sample.kintai.payroll.domain.PayrollExportId;
import jp.co.sample.kintai.payroll.domain.PayrollExportNotFoundException;
import jp.co.sample.kintai.payroll.domain.PayrollExportRepository;
import jp.co.sample.kintai.payroll.domain.PayrollRow;
import jp.co.sample.kintai.shared.application.AccessDeniedException;
import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.Requester;
import jp.co.sample.kintai.shared.domain.Role;
import jp.co.sample.kintai.workrule.application.WorkRuleMasterService;
import jp.co.sample.kintai.workrule.domain.AnnualScheduledHours;

/**
 * 給与計算用データの出力（BR-18）。
 *
 * <p><strong>ここは値を作らない。</strong>
 * 労働時間は {@code attendance} が保存した月次清算を読み、
 * 日数も {@code attendance} に数えさせ、状態は {@code approval} に問う。
 * 写すと、制度ごとの数え方を直したときに片方だけが古くなる（CLAUDE.md 落とし穴 67）。
 *
 * <p><strong>社員ごとの事情は結果へ、依頼そのものの不備は例外へ</strong>（落とし穴 60）。
 * 未締めの社員は除外して返し、対象月が終わっていない依頼は例外にする。
 */
@Service
public class PayrollExportService {

    private final EmployeeDirectoryService employees;
    private final MonthlyAttendanceService attendances;
    private final MonthlySettlementService settlements;
    private final WorkRuleMasterService workRules;
    private final PayrollExportRepository exports;

    public PayrollExportService(EmployeeDirectoryService employees,
                                MonthlyAttendanceService attendances,
                                MonthlySettlementService settlements,
                                WorkRuleMasterService workRules,
                                PayrollExportRepository exports) {
        this.employees = employees;
        this.attendances = attendances;
        this.settlements = settlements;
        this.workRules = workRules;
        this.exports = exports;
    }

    /**
     * 対象月の給与連携データを作り、記録を残す。
     *
     * <p>返すのは<strong>記録</strong>であり、CSV の本文ではない。
     * 本文は {@link #rowsOf} が記録の対象社員から作り直す。
     * 「除外した社員の一覧」と CSV を同じ応答に載せられないためである（API設計書 1.1）。
     */
    @Transactional
    public PayrollExport export(Requester requester, YearMonth month) {
        requireHumanResources(requester);
        // ★ 判定式は写さない。05 の公開メソッドを呼ぶ（落とし穴 67）
        attendances.requireMonthFinished(month);

        // ★ 分母を先に確かめる。カレンダー未登録の年度は 365 日として計算されてしまい、
        //   割増の単価が法定を大きく下回る（落とし穴 123）
        AnnualScheduledHours annual = workRules.annualScheduledHours(requester,
                AnnualScheduledHours.fiscalYearOf(month));

        List<Employee> targets = employees.employedDuring(requester, monthRange(month));
        Map<EmployeeId, AttendanceState> states =
                attendances.statesOf(month, targets.stream().map(Employee::id).toList());

        List<EmployeeNumber> duplicated = duplicatedNumbers(targets);

        Map<EmployeeId, Optional<ExclusionReason>> decided = new LinkedHashMap<>();
        for (Employee employee : targets) {
            decided.put(employee.id(), reasonToExclude(employee, month,
                    states.get(employee.id()), duplicated));
        }

        PayrollExport export = PayrollExport.of(month, requester.employeeId(),
                annual, decided);
        exports.save(export);
        return exports.find(export.id()).orElseThrow(
                () -> new IllegalStateException("保存した出力の記録が読み出せません"));
    }

    /**
     * 除外する理由。空なら出力する。
     *
     * <p><strong>順序に意味がある。</strong>
     * 社員番号の重複は「誰の行か」が決まらないので最初に見る。
     * 打刻の有無は状態より先に見る。状態を先に見ると、
     * 1 日も打刻の無い社員が「下書き」として扱われる（落とし穴 120）。
     */
    private Optional<ExclusionReason> reasonToExclude(
            Employee employee, YearMonth month, AttendanceState state,
            List<EmployeeNumber> duplicated) {
        if (duplicated.contains(employee.number())) {
            return Optional.of(ExclusionReason.DUPLICATE_EMPLOYEE_NUMBER);
        }
        if (state == AttendanceState.CLOSED) {
            return Optional.empty();
        }
        if (!settlements.hasTimeClockIn(employee.id(), month)) {
            return Optional.of(ExclusionReason.NO_ATTENDANCE_RECORD);
        }
        return Optional.of(switch (state) {
            case DRAFT -> settlements.isCalculable(employee.id(), month)
                    ? ExclusionReason.NOT_SUBMITTED
                    : ExclusionReason.NOT_SUBMITTABLE;
            case SUBMITTED -> ExclusionReason.NOT_APPROVED;
            case APPROVED -> ExclusionReason.NOT_CLOSED;
            // 締め済みは上で返している。ここに来るのは分岐の追加漏れ
            case CLOSED -> throw new IllegalStateException("締め済みは除外しません");
        });
    }

    /**
     * 対象月に 2 人以上が名乗っている社員番号。
     *
     * <p>社員番号の一意制約は在籍者だけを見る部分一意インデックスなので、
     * <strong>退職者の番号は再利用できる</strong>（落とし穴 121）。
     * 月中退職と同月の入社が重なると、CSV の名寄せの鍵が重複する。
     */
    private List<EmployeeNumber> duplicatedNumbers(List<Employee> targets) {
        Map<EmployeeNumber, Integer> counts = new LinkedHashMap<>();
        for (Employee employee : targets) {
            counts.merge(employee.number(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * 記録から CSV の行を作り直す。
     *
     * <p><strong>対象社員は記録に閉じる。</strong>
     * 在籍者を数え直すと、記録のあとに締めた社員が増えて行数が変わり、
     * 記録が「何を出したか」を指さなくなる（落とし穴 122）。
     */
    @Transactional(readOnly = true)
    public List<PayrollRow> rowsOf(Requester requester, PayrollExportId id) {
        requireHumanResources(requester);
        PayrollExport export = exports.find(id)
                .orElseThrow(() -> new PayrollExportNotFoundException(id));

        List<EmployeeId> included = export.includedEmployeeIds();
        Map<EmployeeId, Employee> byId = new LinkedHashMap<>();
        for (Employee employee : employees.findByIds(requester, included)) {
            byId.put(employee.id(), employee);
        }

        List<PayrollRow> rows = new ArrayList<>();
        for (EmployeeId employeeId : included) {
            Employee employee = byId.get(employeeId);
            if (employee == null) {
                throw new IllegalStateException("記録した対象社員が見つかりません: " + employeeId);
            }
            rows.add(rowOf(employee, export.month()));
        }
        // ★ 社員番号 → 入社日の順。番号は在籍者のあいだでしか一意でないので、
        //   番号だけでは同じ月に 2 人並ぶことがある
        return rows.stream()
                .sorted(java.util.Comparator
                        .comparing((PayrollRow row) -> row.employeeNumber().value())
                        .thenComparing(row -> row.period().from()))
                .toList();
    }

    /**
     * 記録に載っている社員。
     *
     * <p>除外した社員を<strong>社員番号で</strong>返すために要る。
     * 人事は「誰を締めればよいか」を直接読むので、UUID では行動できない。
     */
    @Transactional(readOnly = true)
    public List<Employee> employeesOf(Requester requester, PayrollExport export) {
        requireHumanResources(requester);
        return employees.findByIds(requester, export.targets().keySet());
    }

    /** 出力の記録（監査）。 */
    @Transactional(readOnly = true)
    public List<PayrollExport> list(Requester requester, Optional<YearMonth> month,
                                    int limit) {
        requireHumanResources(requester);
        return exports.findByMonth(month, limit);
    }

    /** 1 社員ぶんの行。<strong>保存済みの月次清算を読む。計算し直さない。</strong> */
    private PayrollRow rowOf(Employee employee, YearMonth month) {
        EmployeeId employeeId = employee.id();
        MonthlySettlement settlement = settlements.find(employeeId, month).orElseThrow(
                () -> new IllegalStateException(
                        "締め済みなのに月次清算がありません: %s / %s".formatted(employeeId, month)));
        // ★ 清算期間は保存済みの月次清算から渡す。引き直すと、締めたあとに登録された
        //   退職日で期間が縮み、同じ出力の記録から出る CSV が変わる（落とし穴 122）
        MonthlyDayCounts days = settlements.dayCountsIn(employeeId, settlement.period());

        Duration over60 = settlement.overtimeOver60Time();
        return new PayrollRow(employee.number(), month, settlement.period().period(),
                settlement.workingTimeSystem(),
                days.monthlyScheduledDays(), days.scheduledDays(), days.attendedDays(),
                days.paidLeaveDays(), days.absentDays(),
                settlement.workingTime(),
                settlement.scheduledInsideTime(), settlement.beyondScheduledTime(),
                settlement.overtimeTime().minus(over60), over60,
                settlement.legalHolidayTime(), settlement.nightTime(),
                settlement.scheduledTotalTime(), settlement.shortageTime());
    }

    private DateRange monthRange(YearMonth month) {
        return DateRange.ofMonth(month);
    }

    /**
     * 全社員の賃金の基礎になるデータなので、人事に限る。
     *
     * <p>閲覧範囲で絞る一覧（[06 API設計書 1](../06_年次有給休暇/)）と違い、
     * ここは本人のぶんを返す API ではない。
     */
    private void requireHumanResources(Requester requester) {
        if (!requester.has(Role.HR)) {
            throw new AccessDeniedException();
        }
    }
}
