package jp.co.sample.kintai.attendance.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sample.kintai.attendance.domain.DailyAttendance;
import jp.co.sample.kintai.attendance.domain.DailyAttendanceCalculator;
import jp.co.sample.kintai.attendance.domain.DailyAttendanceRepository;
import jp.co.sample.kintai.attendance.domain.TimeClockEvent;
import jp.co.sample.kintai.attendance.domain.TimeClockEventRepository;
import jp.co.sample.kintai.attendance.domain.TimeClockSequence;
import jp.co.sample.kintai.shared.domain.BusinessZone;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.workrule.domain.CompanyCalendarRepository;
import jp.co.sample.kintai.workrule.domain.WorkRule;
import jp.co.sample.kintai.workrule.domain.WorkRuleRepository;

/**
 * 打刻のユースケース。<strong>トランザクション境界はこの層に置く</strong>
 * （アーキテクチャ設計書 6.1）。
 *
 * <p>1 回の打刻で「打刻の追記」と「日次勤怠の再計算」の 2 つを行う。
 * これが 1 トランザクションであることが、
 * 「打刻は残ったが集計が古いまま」という中途半端な状態を防ぐ。
 */
@Service
public class TimeClockService {

    /**
     * 開いている勤務日を遡って探す範囲。
     *
     * <p>日をまたぐ勤務は最長でも数十時間なので、2 日あれば足りる。
     * <strong>無期限に遡らせない。</strong> 退勤し忘れが何日も前に残っていると、
     * 今日の出勤が「その日の勤務の続き」として扱われてしまう。
     */
    private static final int OPEN_WORK_DATE_LOOKBACK_DAYS = 2;

    private final TimeClockEventRepository timeClocks;
    private final DailyAttendanceRepository dailyAttendances;
    private final WorkRuleRepository workRules;
    private final CompanyCalendarRepository calendar;
    private final Clock clock;

    public TimeClockService(TimeClockEventRepository timeClocks,
                            DailyAttendanceRepository dailyAttendances,
                            WorkRuleRepository workRules,
                            CompanyCalendarRepository calendar,
                            Clock clock) {
        this.timeClocks = timeClocks;
        this.dailyAttendances = dailyAttendances;
        this.workRules = workRules;
        this.calendar = calendar;
        this.clock = clock;
    }

    /**
     * 打刻する。
     *
     * @param occurredAt 打刻時刻。空なら {@link Clock} から解決する。
     *                   <strong>プレゼンテーション層で埋めない</strong>（AR-09）
     */
    @Transactional
    public PunchResult punch(EmployeeId employeeId, TimeClockEvent.Type type,
                             Optional<LocalDateTime> occurredAt) {
        LocalDateTime at = occurredAt.orElseGet(() -> LocalDateTime.now(clock));
        LocalDate workDate = resolveWorkDate(employeeId, at);
        TimeClockEvent event = type.at(at);

        // ★ 並びが不正なら追記しない。ここで例外が出れば打刻は記録されない
        TimeClockSequence merged = timeClocks.findByWorkDate(employeeId, workDate)
                .plus(event);
        merged.validateTransitions();

        timeClocks.append(employeeId, workDate, event, employeeId);
        return calculateIfClosed(employeeId, workDate, merged);
    }

    /**
     * その打刻が属する勤務日を決める（BR-03）。
     *
     * <p><strong>打刻した暦日をそのまま勤務日にしない。</strong>
     * 日をまたぐ勤務では、退勤や休憩を「出勤した日の勤務」に追記しなければならない。
     * 暦日で振り分けると、日跨ぎ勤務の退勤が翌日の勤務として記録され、
     * <strong>出勤の無い日に退勤だけが残る。</strong>
     */
    private LocalDate resolveWorkDate(EmployeeId employeeId, LocalDateTime at) {
        LocalDate punchedOn = at.toLocalDate();
        return timeClocks.findOpenWorkDate(employeeId,
                        punchedOn.minusDays(OPEN_WORK_DATE_LOOKBACK_DAYS))
                .orElse(punchedOn);
    }

    /**
     * 退勤まで完了していれば集計する。
     *
     * <p><strong>就業規則が無いことを理由に打刻を拒否しない。</strong>
     * 打刻は労働時間の一次証拠であり、計算側の都合で記録を止めると
     * 働いた事実が残らない（CLAUDE.md 落とし穴 19）。
     * 「打刻は成功し、計算だけが行われなかった」という状態を返す。
     */
    private PunchResult calculateIfClosed(EmployeeId employeeId, LocalDate workDate,
                                          TimeClockSequence sequence) {
        if (!sequence.isClosed()) {
            return PunchResult.notClosed(workDate);
        }
        Optional<WorkRule> rule = workRules.findEffective(employeeId, workDate);
        if (rule.isEmpty()) {
            return PunchResult.workRuleNotFound(workDate);
        }
        DailyAttendance attendance = new DailyAttendanceCalculator(calendar)
                .calculate(workDate, sequence, rule.get());
        dailyAttendances.save(employeeId, attendance, rule.get().id());
        return PunchResult.calculated(workDate, attendance);
    }

    /** 現在時刻（会社基準の壁掛け時計）。画面が「今日」を組み立てるのに使う。 */
    public LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(BusinessZone.ID));
    }
}
