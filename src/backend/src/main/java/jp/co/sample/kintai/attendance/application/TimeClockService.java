package jp.co.sample.kintai.attendance.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
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
import jp.co.sample.kintai.shared.application.AccessDeniedException;
import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.MonthClosureQuery;
import jp.co.sample.kintai.shared.domain.Requester;
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

    /**
     * 未退勤の勤務日を遡って探す月数の上限。
     *
     * <p>締めは毎月行われるので、実際には 1〜2 か月で止まる。
     * <strong>上限を置くのは、締めが長く滞った社員で全期間を走査しないため。</strong>
     */
    private static final int UNCLOSED_LOOKBACK_MONTHS = 12;

    private final TimeClockEventRepository timeClocks;
    private final DailyAttendanceRepository dailyAttendances;
    private final WorkRuleRepository workRules;
    private final CompanyCalendarRepository calendar;
    private final MonthClosureQuery monthClosure;
    private final Clock clock;

    public TimeClockService(TimeClockEventRepository timeClocks,
                            DailyAttendanceRepository dailyAttendances,
                            WorkRuleRepository workRules,
                            CompanyCalendarRepository calendar,
                            MonthClosureQuery monthClosure,
                            Clock clock) {
        this.monthClosure = monthClosure;
        this.timeClocks = timeClocks;
        this.dailyAttendances = dailyAttendances;
        this.workRules = workRules;
        this.calendar = calendar;
        this.clock = clock;
    }

    /**
     * 打刻する。
     *
     * <p><strong>打刻できるのは本人だけである</strong>（要件定義書 4 章）。
     * 上長も人事も他人の打刻はできない。打刻は<strong>一次証拠</strong>であり、
     * 本人以外が作れると「その時刻にその人がいた」という記録の意味が消える。
     * 代理で直す必要があるときは訂正申請（BR-09）を通す。
     *
     * @param requester  依頼者。<strong>「誰の依頼か」を引数で受け取る</strong>
     *                   （CLAUDE.md 落とし穴 42）
     * @param occurredAt 打刻時刻。空なら {@link Clock} から解決する。
     *                   <strong>プレゼンテーション層で埋めない</strong>（AR-09）
     */
    @Transactional
    public PunchResult punch(Requester requester, EmployeeId employeeId,
                             TimeClockEvent.Type type,
                             Optional<LocalDateTime> occurredAt) {
        if (!requester.isSelf(employeeId)) {
            throw new AccessDeniedException();
        }
        LocalDateTime at = occurredAt.orElseGet(() -> LocalDateTime.now(clock));
        LocalDate workDate = resolveWorkDate(employeeId, type, at);

        // ★ 判定に使う月は勤務日が属する月である。
        //   打刻時刻の月で判定すると、3/31 22:00 出勤 → 4/1 06:00 退勤の退勤打刻が、
        //   締め済みの 3 月分を 4 月扱いで書き込めてしまう
        if (!monthClosure.acceptsTimeClock(employeeId, YearMonth.from(workDate))) {
            throw new MonthNotOpenForTimeClockException(workDate);
        }
        TimeClockEvent event = type.at(at);

        // ★ 並びが不正なら追記しない。ここで例外が出れば打刻は記録されない
        TimeClockSequence merged = timeClocks.findByWorkDate(employeeId, workDate)
                .plus(event);
        merged.validateTransitions();

        timeClocks.append(employeeId, workDate, event, employeeId);
        return calculateIfClosed(employeeId, workDate, merged,
                unclosedWorkDates(employeeId, workDate));
    }

    /**
     * その打刻が属する勤務日を決める（BR-03）。
     *
     * <p><strong>打刻した暦日をそのまま勤務日にしない。</strong>
     * 日をまたぐ勤務では、退勤や休憩を「出勤した日の勤務」に追記しなければならない。
     * 暦日で振り分けると、日跨ぎ勤務の退勤が翌日の勤務として記録され、
     * <strong>出勤の無い日に退勤だけが残る。</strong>
     *
     * <p>ただし<strong>出勤だけは別扱いにする。</strong>
     * 開いている勤務日にはすでに出勤があるため、そこへ出勤を足すと状態機械が壊れる。
     * 前日の退勤を打ち忘れた社員が<strong>翌朝の出勤打刻を拒否される</strong>ことになり、
     * 別の日の不整合でその日の労働の記録を止めてしまう（落とし穴 19）。
     */
    private LocalDate resolveWorkDate(EmployeeId employeeId, TimeClockEvent.Type type,
                                      LocalDateTime at) {
        LocalDate punchedOn = at.toLocalDate();
        if (type == TimeClockEvent.Type.CLOCK_IN) {
            // ★ 出勤は必ず新しい勤務日を始める。
            //   開いている勤務日にはすでに出勤があるので、そこへ足すと状態機械が壊れ、
            //   前日の退勤を打ち忘れた社員は翌朝そもそも出勤打刻できなくなる。
            //   別の日の不整合で、その日の労働の記録を止めてはいけない（落とし穴 19）。
            //   打ち忘れた日は unclosedWorkDates の警告として返す
            return punchedOn;
        }
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
                                          TimeClockSequence sequence,
                                          java.util.List<LocalDate> unclosed) {
        if (!sequence.isClosed()) {
            return PunchResult.notClosed(workDate, unclosed);
        }
        Optional<WorkRule> rule = workRules.findEffective(employeeId, workDate);
        if (rule.isEmpty()) {
            return PunchResult.workRuleNotFound(workDate, unclosed);
        }
        DailyAttendance attendance = new DailyAttendanceCalculator(calendar)
                .calculate(workDate, sequence, rule.get());
        dailyAttendances.save(employeeId, attendance, rule.get().id());
        return PunchResult.calculated(workDate, attendance, unclosed);
    }

    /**
     * まだ退勤していない過去の勤務日（BR-03）。
     *
     * <p><strong>前日 1 日だけを見ない。</strong>
     * 金曜に退勤を打ち忘れて月曜に出勤したケースを取りこぼす。
     *
     * <p>探し始めるのは<strong>締めていない最も古い月の初日</strong>。
     * 締め済みの月には未退勤の日が残らない（提出前の検査が通らないため）。
     *
     * <p><strong>今まさに打刻した日は含めない。</strong>
     * 出勤した直後は当然まだ退勤していないので、含めると毎回警告が出る。
     */
    private java.util.List<LocalDate> unclosedWorkDates(EmployeeId employeeId,
                                                        LocalDate workDate) {
        YearMonth from = YearMonth.from(workDate);
        for (int i = 0; i < UNCLOSED_LOOKBACK_MONTHS
                && !monthClosure.isClosed(employeeId, from.minusMonths(1)); i++) {
            from = from.minusMonths(1);
        }
        var period = new jp.co.sample.kintai.shared.domain.DateRange(
                from.atDay(1), workDate.plusDays(1));
        return timeClocks.findUnclosedWorkDates(employeeId, period).stream()
                .filter(unclosed -> !unclosed.equals(workDate))
                .toList();
    }

    /**
     * その勤務日の日次勤怠を計算し直す（BR-09）。
     *
     * <p>訂正の承認が使う。<strong>打刻から日次を作る手順をここに 1 つだけ置く。</strong>
     * 訂正側に同じ手順をもう 1 つ書くと、
     * 丸めや勤務日の扱いを直したときに片方だけが古くなる。
     *
     * @return 計算できたか。退勤していない日や就業規則の無い日は計算されない
     */
    @Transactional
    public PunchResult recalculate(EmployeeId employeeId, LocalDate workDate) {
        return calculateIfClosed(employeeId, workDate,
                timeClocks.findByWorkDate(employeeId, workDate),
                unclosedWorkDates(employeeId, workDate));
    }

    /** 現在時刻（会社基準の壁掛け時計）。画面が「今日」を組み立てるのに使う。 */
    public LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(BusinessZone.ID));
    }

    /**
     * その月はもう直接打刻を受け付けない（BR-10）。
     *
     * <p>提出済み・承認済み・締め済みが該当する。
     * <strong>状態が変われば通るので {@code CONFLICT}（409）である。</strong>
     * 権限の問題ではないので 403 にはしない。
     */
    public static final class MonthNotOpenForTimeClockException extends DomainException {

        @java.io.Serial
        private static final long serialVersionUID = 1L;

        MonthNotOpenForTimeClockException(LocalDate workDate) {
            super("提出済み・締め済みの月には打刻できません: 勤務日 " + workDate);
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:month-not-open";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.CONFLICT;
        }

        @Override
        public String title() {
            return "その月には打刻できません";
        }
    }
}
