package jp.co.sample.kintai.attendance.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.workrule.domain.WorkRuleId;

/** 日次勤怠のポート。 */
public interface DailyAttendanceRepository {

    /**
     * 保存する。同じ社員・同じ勤務日の行があれば置き換える（再計算）。
     *
     * <p>内訳（slices）も<strong>まるごと入れ替える。</strong>
     * 部分更新にすると、再計算で区間の数が減ったときに古い区間が残り、
     * 内訳の合計が実労働時間と食い違う。
     *
     * @param workRuleId 計算に使った版。あとから「なぜこの残業時間か」を説明するために残す
     */
    void save(EmployeeId employeeId, DailyAttendance attendance, WorkRuleId workRuleId);

    Optional<DailyAttendance> find(EmployeeId employeeId, LocalDate workDate);

    /** 期間分をまとめて読む。月次の集計で日ごとに引くと N+1 になる。 */
    List<DailyAttendance> findByPeriod(EmployeeId employeeId, DateRange period);
}
