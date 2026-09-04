package jp.co.sample.kintai.attendance.application;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sample.kintai.attendance.domain.DailyAttendance;
import jp.co.sample.kintai.attendance.domain.DailyAttendanceRepository;
import jp.co.sample.kintai.shared.application.AccessDeniedException;
import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.EmployeeVisibility;
import jp.co.sample.kintai.shared.domain.Requester;

/**
 * 日次勤怠の参照。
 *
 * <p>{@code readOnly} なトランザクション境界をこの層に置く（アーキテクチャ設計書 6.1）。
 *
 * <p><strong>閲覧範囲の判定をここで行う</strong>（要件定義書 4.1）。
 * 「配下部署の社員か」は組織の状態に依存する業務判断なので、
 * Spring Security の認可設定では表現できない。
 *
 * <p>依頼者は<strong>引数で受け取る。</strong>
 * 認証の枠組みから読みにいく形にすると、ユースケースが
 * 「誰の依頼か」を差し替えられなくなり、バッチからも呼べなくなる
 * （CLAUDE.md 落とし穴 42）。
 */
@Service
@Transactional(readOnly = true)
public class AttendanceQueryService {

    private final DailyAttendanceRepository dailyAttendances;
    private final EmployeeVisibility visibility;

    public AttendanceQueryService(DailyAttendanceRepository dailyAttendances,
                                  EmployeeVisibility visibility) {
        this.dailyAttendances = dailyAttendances;
        this.visibility = visibility;
    }

    public Optional<DailyAttendance> find(Requester requester, EmployeeId employeeId,
                                          LocalDate workDate) {
        // ★ 基準日はその勤務日。今日の組織で過去の勤怠の可否を決めない。
        //   異動した部下の異動前の勤怠を、旧上長が見られなくなるのを防ぐ
        requireVisible(requester, employeeId, workDate);
        return dailyAttendances.find(employeeId, workDate);
    }

    /**
     * その月の日次勤怠。
     *
     * <p>期間を<strong>暦月の半開区間</strong>で組み立てる。
     * {@code atEndOfMonth()} を上限にすると月末日が漏れる。
     */
    public List<DailyAttendance> findByMonth(Requester requester, EmployeeId employeeId,
                                            YearMonth month) {
        requireVisible(requester, employeeId, month.atEndOfMonth());
        return dailyAttendances.findByPeriod(employeeId,
                new DateRange(month.atDay(1), month.plusMonths(1).atDay(1)));
    }

    private void requireVisible(Requester requester, EmployeeId target, LocalDate asOf) {
        if (!visibility.canView(requester, target, asOf)) {
            throw new AccessDeniedException();
        }
    }
}
