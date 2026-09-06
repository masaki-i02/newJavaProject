package jp.co.sample.kintai.leave.application;

import java.time.Clock;
import java.util.ArrayList;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sample.kintai.attendance.domain.DailyAttendanceRepository;
import jp.co.sample.kintai.employee.domain.Employee;
import jp.co.sample.kintai.leave.domain.AttendanceRate;
import jp.co.sample.kintai.leave.domain.GrantDecision;
import jp.co.sample.kintai.leave.domain.GrantSchedule;
import jp.co.sample.kintai.leave.domain.LeaveEntitlement;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrant;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrantId;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrantRepository;
import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.PaidLeaveDays;
import jp.co.sample.kintai.workrule.domain.CompanyCalendar;

/**
 * 1 人ぶんの付与を、独立したトランザクションで実行する（BR-14）。
 *
 * <p><strong>別のクラスに置く。</strong> 同じクラスから {@code @Transactional} を呼ぶと
 * Spring のプロキシを通らず、100 人ぶんが 1 つのトランザクションになる。
 * 1 人の計算が失敗すると 99 人の付与が巻き戻る（CLAUDE.md 落とし穴 59）。
 */
@Service
public class PaidLeaveGrantExecutor {

    private final PaidLeaveGrantRepository grants;
    private final DailyAttendanceRepository dailyAttendances;
    private final PaidLeaveDays paidLeaveDays;
    private final CompanyCalendar calendar;
    private final Clock clock;

    public PaidLeaveGrantExecutor(PaidLeaveGrantRepository grants,
                                  DailyAttendanceRepository dailyAttendances,
                                  PaidLeaveDays paidLeaveDays,
                                  CompanyCalendar calendar,
                                  Clock clock) {
        this.grants = grants;
        this.dailyAttendances = dailyAttendances;
        this.paidLeaveDays = paidLeaveDays;
        this.calendar = calendar;
        this.clock = clock;
    }

    /**
     * 基準日までに到来した、その社員の未処理の付与をすべて作る。
     *
     * <p><strong>古い順に作る。</strong> 1 回目の出勤率を判定するには
     * 0 回目の年休の取得日が要る（年休は出勤扱い）。
     *
     * <p><strong>付与日に在籍している場合に限る。</strong>
     * 除かないと退職者に毎年 20 日が積み上がる。しかも退職者の算定期間は
     * 在籍期間で絞ると全労働日 0 になり、出勤率の判定を必ず通る（落とし穴 92）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Outcome> grantFor(Employee employee, LocalDate asOf) {
        var schedule = new GrantSchedule(employee.hiredOn());
        List<Outcome> outcomes = new ArrayList<>();
        for (int index : schedule.indexesDueOn(asOf).toArray()) {
            LocalDate grantedOn = schedule.grantDateOf(index);
            if (!employee.isActiveOn(grantedOn)) {
                continue;
            }
            if (grants.find(employee.id(), grantedOn).isPresent()) {
                outcomes.add(new Outcome(grantedOn, Optional.empty(), Optional.empty()));
                continue;
            }
            AttendanceRate rate = assess(employee, schedule.assessmentPeriodOf(index));
            PaidLeaveGrant grant = decide(employee, index, grantedOn, rate);
            grants.save(grant);
            outcomes.add(new Outcome(grantedOn,
                    Optional.of(grant.decision()), Optional.of(rate)));
        }
        return List.copyOf(outcomes);
    }

    /** 不付与だった付与を、現在の実績と人事の申告で判定し直す（BR-14）。 */
    @Transactional
    public PaidLeaveGrant reassess(Employee employee, PaidLeaveGrant grant,
                                   int deemedAttendedDays, String deemedReason) {
        var schedule = new GrantSchedule(employee.hiredOn());
        AttendanceRate measured = assess(employee, schedule.assessmentPeriodOf(grant.grantIndex()));
        // ★ 人事が送った値は業務エラーとして弾く。
        //   AttendanceRate の compact constructor に任せると IllegalArgumentException になり、
        //   理由の載らない 500 で返る（API設計書 4.2 は 422 を求めている）
        requireAcceptable(measured, deemedAttendedDays, deemedReason);
        var rate = new AttendanceRate(measured.totalWorkingDays(), measured.attendedDays(),
                deemedAttendedDays, deemedReason);

        PaidLeaveGrant reassessed = grant.reassess(rate, LocalDateTime.now(clock));
        grants.update(reassessed, grant.version());
        return reassessed;
    }

    /**
     * 人事の申告を受け付けられるか（BR-14）。
     *
     * <p>実績（{@code measured}）と突き合わせないと判定できないので、ここに置く。
     */
    private static void requireAcceptable(AttendanceRate measured, int deemedAttendedDays,
                                          String deemedReason) {
        if (deemedAttendedDays < 0) {
            throw DeemedAttendanceRejectedException.negative(deemedAttendedDays);
        }
        if (deemedAttendedDays > 0 && (deemedReason == null || deemedReason.isBlank())) {
            throw DeemedAttendanceRejectedException.reasonMissing();
        }
        if (measured.attendedDays() + deemedAttendedDays > measured.totalWorkingDays()) {
            throw DeemedAttendanceRejectedException.exceedsTotal(measured.attendedDays(),
                    deemedAttendedDays, measured.totalWorkingDays());
        }
    }

    private AttendanceRate assess(Employee employee, DateRange period) {
        Set<LocalDate> leaveDays = paidLeaveDays.approvedOn(employee.id(), period);
        return new AttendanceRateCalculator(calendar).of(employee, period,
                dailyAttendances.findByPeriod(employee.id(), period), leaveDays);
    }

    private PaidLeaveGrant decide(Employee employee, int index, LocalDate grantedOn,
                                  AttendanceRate rate) {
        GrantDecision decision = rate.meetsThreshold()
                ? new GrantDecision.Granted(LeaveEntitlement.of(index).days())
                : new GrantDecision.Withheld();
        return new PaidLeaveGrant(PaidLeaveGrantId.generate(), employee.id(), index,
                grantedOn, rate, decision, LocalDateTime.now(clock), 1L);
    }

    /**
     * 1 件の付与の結果。
     *
     * @param decision 空なら「既に処理済みなので何もしなかった」
     */
    public record Outcome(LocalDate grantedOn, Optional<GrantDecision> decision,
                          Optional<AttendanceRate> rate) {
    }
}
