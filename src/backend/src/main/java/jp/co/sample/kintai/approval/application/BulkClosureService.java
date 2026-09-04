package jp.co.sample.kintai.approval.application;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import jp.co.sample.kintai.approval.domain.AttendanceState;
import jp.co.sample.kintai.approval.domain.MonthlyAttendance;
import jp.co.sample.kintai.approval.domain.MonthlyAttendanceRepository;
import jp.co.sample.kintai.employee.domain.Employee;
import jp.co.sample.kintai.employee.domain.EmployeeRepository;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.Requester;

/**
 * 一括締め（BR-10）。
 *
 * <p><strong>{@link MonthlyAttendanceService} とは別のクラスにする。</strong>
 * 1 社員ずつ独立したトランザクションで処理したいが、
 * 同じクラスの中から呼ぶと Spring のプロキシを通らず、
 * <strong>{@code @Transactional} が効かないまま 1 つのトランザクションになる。</strong>
 * そうなると 1 件の失敗で 99 件の締めが巻き戻る。
 *
 * <p>このクラス自体には {@code @Transactional} を付けない。
 * 境界は {@code MonthlyAttendanceService.close} の側にある。
 */
@Service
public class BulkClosureService {

    private final MonthlyAttendanceService attendanceService;
    private final MonthlyAttendanceRepository attendances;
    private final EmployeeRepository employees;

    public BulkClosureService(MonthlyAttendanceService attendanceService,
                              MonthlyAttendanceRepository attendances,
                              EmployeeRepository employees) {
        this.attendanceService = attendanceService;
        this.attendances = attendances;
        this.employees = employees;
    }

    /**
     * まとめて締める。
     *
     * <p><strong>1 人でも締められない社員がいても、全体を失敗させない。</strong>
     * 100 人のうち 1 人が未承認なだけで 99 人の締めが止まると運用が回らない。
     *
     * <p>ただし<strong>社員ごとの事情と、依頼そのものの不備は分ける。</strong>
     * 人事でない・対象月がまだ終わっていない、は依頼そのものが成り立たないので
     * 例外のまま伝える。全員を {@code skipped} に並べても、
     * 人事は「自分に権限が無い」ことに気づけない。
     *
     * @param employeeIds 対象。<strong>空なら全社員</strong>
     */
    public BulkClosureResult closeAll(Requester requester, YearMonth month,
                                      Optional<List<EmployeeId>> employeeIds) {
        List<EmployeeId> targets = employeeIds.orElseGet(() -> allEmployeeIds(month));
        List<BulkClosureResult.Skipped> skipped = new ArrayList<>();
        int closed = 0;

        for (EmployeeId employeeId : targets) {
            // 締める前の状態を控える。締められなかった理由として返すため
            AttendanceState before = stateOf(employeeId, month);
            try {
                attendanceService.close(requester, employeeId, month,
                        attendances.currentVersion(employeeId, month));
                closed++;
            } catch (MonthlyAttendance.InvalidTransitionException e) {
                skipped.add(new BulkClosureResult.Skipped(employeeId, before,
                        reasonFor(before)));
            } catch (MonthlyAttendanceService.AttendanceNotFoundException e) {
                skipped.add(new BulkClosureResult.Skipped(employeeId,
                        AttendanceState.DRAFT, reasonFor(AttendanceState.DRAFT)));
            } catch (OptimisticLockingFailureException e) {
                skipped.add(new BulkClosureResult.Skipped(employeeId, before,
                        "他の利用者が先に更新しました"));
            }
        }
        return new BulkClosureResult(month, closed, skipped);
    }

    /**
     * 対象月に在籍した社員。<strong>退職者も含める。</strong>
     *
     * <p>3/31 退職の社員の 3 月分は締めなければならない。
     * 退職者を外すと、その月が永久に締まらない。
     */
    private List<EmployeeId> allEmployeeIds(YearMonth month) {
        return employees.findAll(month.atEndOfMonth(), true).stream()
                .map(Employee::id).toList();
    }

    /** 行が無い月は下書き相当（API設計書 4 の 6）。 */
    private AttendanceState stateOf(EmployeeId employeeId, YearMonth month) {
        return attendances.find(employeeId, month)
                .map(attendance -> attendance.status().state())
                .orElse(AttendanceState.DRAFT);
    }

    /**
     * 締められなかった理由。
     *
     * <p><strong>網羅性検査つきの {@code switch} で書く。</strong>
     * 状態を足したときに、ここが「その他」に落ちて
     * 人事に理由の分からない結果が返るのを防ぐ。
     */
    private static String reasonFor(AttendanceState state) {
        return switch (state) {
            case DRAFT -> "提出されていません";
            case SUBMITTED -> "承認されていません";
            case CLOSED -> "すでに締め済みです";
            // 承認済なら締められるはずなので、ここへは来ない。
            // 来たとすれば読み取りと締めの間に状態が変わっている
            case APPROVED -> "他の利用者が先に更新しました";
        };
    }
}
