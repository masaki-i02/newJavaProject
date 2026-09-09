package jp.co.sample.kintai.leave.application;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sample.kintai.approval.application.MonthlyAttendanceService;
import jp.co.sample.kintai.approval.domain.Approver;
import jp.co.sample.kintai.approval.domain.ApproverPolicy;
import jp.co.sample.kintai.approval.domain.NotApproverException;
import jp.co.sample.kintai.attendance.application.MonthlySettlementService;
import jp.co.sample.kintai.attendance.domain.DailyAttendance;
import jp.co.sample.kintai.attendance.domain.DailyAttendanceRepository;
import jp.co.sample.kintai.employee.domain.Employee;
import jp.co.sample.kintai.employee.domain.EmployeeRepository;
import jp.co.sample.kintai.leave.domain.LeaveRequestEvent;
import jp.co.sample.kintai.leave.domain.LeaveRequestEventKind;
import jp.co.sample.kintai.leave.domain.LeaveRequestStatus;
import jp.co.sample.kintai.leave.domain.NotTheRequesterException;
import jp.co.sample.kintai.leave.domain.PaidLeaveBalance;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrant;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrantId;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrantRepository;
import jp.co.sample.kintai.leave.domain.PaidLeaveRequest;
import jp.co.sample.kintai.leave.domain.PaidLeaveRequestId;
import jp.co.sample.kintai.leave.domain.PaidLeaveRequestRepository;
import jp.co.sample.kintai.leave.domain.SelfDecisionException;
import jp.co.sample.kintai.shared.application.AccessDeniedException;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.EmployeeVisibility;
import jp.co.sample.kintai.shared.domain.MonthClosureQuery;
import jp.co.sample.kintai.shared.domain.Requester;
import jp.co.sample.kintai.shared.domain.Role;
import jp.co.sample.kintai.workrule.domain.CompanyCalendar;
import jp.co.sample.kintai.workrule.domain.DayType;

/**
 * 年次有給休暇の申請・承認・取消（BR-16）。
 *
 * <p>承認と取消は {@code attendance} / {@code approval} の {@code application} を呼ぶ。
 * 図にある辺（{@code leave → attendance} / {@code leave → approval}）に沿う
 * （アーキテクチャ設計書 3.2）。手順を写すと片方が古くなる（落とし穴 67）。
 */
@Service
public class PaidLeaveRequestService {

    private final PaidLeaveRequestRepository requests;
    private final PaidLeaveGrantRepository grants;
    private final PaidLeaveBalanceService balances;
    private final EmployeeRepository employees;
    private final DailyAttendanceRepository dailyAttendances;
    private final ApproverPolicy approverPolicy;
    private final MonthClosureQuery monthClosure;
    private final MonthlySettlementService settlements;
    private final MonthlyAttendanceService monthlyAttendances;
    private final EmployeeVisibility visibility;
    private final CompanyCalendar calendar;
    private final Clock clock;

    public PaidLeaveRequestService(PaidLeaveRequestRepository requests,
                                   PaidLeaveGrantRepository grants,
                                   PaidLeaveBalanceService balances,
                                   EmployeeRepository employees,
                                   DailyAttendanceRepository dailyAttendances,
                                   ApproverPolicy approverPolicy,
                                   MonthClosureQuery monthClosure,
                                   MonthlySettlementService settlements,
                                   MonthlyAttendanceService monthlyAttendances,
                                   EmployeeVisibility visibility,
                                   CompanyCalendar calendar,
                                   Clock clock) {
        this.requests = requests;
        this.grants = grants;
        this.balances = balances;
        this.employees = employees;
        this.dailyAttendances = dailyAttendances;
        this.approverPolicy = approverPolicy;
        this.monthClosure = monthClosure;
        this.settlements = settlements;
        this.monthlyAttendances = monthlyAttendances;
        this.visibility = visibility;
        this.calendar = calendar;
        this.clock = clock;
    }

    /**
     * 申請する（BR-16）。<strong>本人しか出せない。</strong>
     *
     * <p>代理申請を認めない。時季指定は本人の意思表示であり、
     * 人事でも代わりには出せない（訂正申請と同じ判断）。
     */
    @Transactional
    public PaidLeaveRequest submit(Requester requester, EmployeeId employeeId,
                                   LocalDate leaveDate, Optional<String> reason) {
        // ★ 本人かどうかを最初に見る。集約の検査（落とし穴 58 のために残す）に任せると、
        //   その前に並ぶ 5 つの検査が対象社員に対して実行され、
        //   締め状態・在籍・既存の申請・残日数がエラーの型から読み取れてしまう（要件 4.1）
        if (!requester.isSelf(employeeId)) {
            throw new NotTheRequesterException(employeeId);
        }
        Employee employee = employeeOf(employeeId);
        requireMonthEditable(employeeId, YearMonth.from(leaveDate));
        requireWorkday(leaveDate);
        requireInService(employee, leaveDate);
        requireNoActiveRequest(employeeId, leaveDate);
        requireEnoughLeave(employee, leaveDate);

        LocalDateTime at = LocalDateTime.now(clock);
        PaidLeaveRequest request = PaidLeaveRequest.submit(PaidLeaveRequestId.generate(),
                requester.employeeId(), employeeId, leaveDate, reason, at);
        requests.save(request);
        record(request, Optional.empty(), LeaveRequestEventKind.SUBMIT,
                requester.employeeId(), Optional.empty(), at);
        return request;
    }

    /**
     * 承認する（BR-16）。
     *
     * <p>4 つを 1 トランザクションで実行する。
     * <ol>
     *   <li>先入先出で付与へ配分する（BR-15）</li>
     *   <li>月次清算を計算し直す（所定総から年休の日を除く・BR-05）</li>
     *   <li>提出済みなら月次勤怠を下書きへ戻す（{@code REVERT_BY_LEAVE}）</li>
     *   <li>遷移を証跡に記録する</li>
     * </ol>
     */
    @Transactional
    public LeaveDecisionResult approve(Requester requester, PaidLeaveRequestId id,
                                       long expectedVersion) {
        PaidLeaveRequest request = requestOf(id);
        YearMonth month = YearMonth.from(request.leaveDate());
        // ★ 「誰の依頼か」を最初に見る。あとに置くと、その前の検査が対象社員に対して
        //   実行され、締め状態・所定労働日・在籍・実労働の有無・残日数が
        //   エラーの型から読み取れる（要件 4.1・申請と同じ判断）
        requireNotSelf(requester, request);
        requireApprover(requester, request.employeeId(), month);
        requireMonthEditable(request.employeeId(), month);
        // ★ 申請から承認までの間にカレンダーや在籍が変わりうる。
        //   休日・在籍期間の外の日に年休を消費しても社員には何の利益も無いので、
        //   申請のときと同じ検査をここでも通す
        requireWorkday(request.leaveDate());
        requireInService(employeeOf(request.employeeId()), request.leaveDate());
        requireNotWorked(request);

        // ★ 承認の時点でも残日数を確かめる。申請から承認までの間に
        //   別の申請が先に承認されると、付与日数を超えて承認できてしまう
        PaidLeaveBalance balance = balances.actualBalanceOf(request.employeeId());
        PaidLeaveGrantId grantId = balance.allocationFor(request.leaveDate())
                .orElseThrow(() -> grantMissing(request, balance));

        // ★ 集約も自己承認を検査する。アプリケーション層を通さない経路を塞ぐためであり、
        //   この経路からは requireNotSelf が先に弾くので働かない。
        //   だから集約に直接あてるテスト（UT-LV-30）で確かめる（落とし穴 58）
        LocalDateTime at = LocalDateTime.now(clock);
        PaidLeaveRequest approved = request.approve(requester.employeeId(), grantId, at);

        requests.update(approved, expectedVersion);
        record(approved, Optional.of(LeaveRequestStatus.SUBMITTED),
                LeaveRequestEventKind.APPROVE, requester.employeeId(), Optional.empty(), at);
        applySideEffects(request, requester, id);
        return resultOf(approved);
    }

    /** 却下する（BR-16）。理由が必須。 */
    @Transactional
    public LeaveDecisionResult reject(Requester requester, PaidLeaveRequestId id,
                                      String comment, long expectedVersion) {
        PaidLeaveRequest request = requestOf(id);
        requireNotSelf(requester, request);
        requireRejecter(requester, request.employeeId(),
                YearMonth.from(request.leaveDate()));
        // ★ 締め済みの月でも却下できる。却下は残日数も月次清算も動かさないので、
        //   拒否すると遷移先の無い申請が残るだけである
        LocalDateTime at = LocalDateTime.now(clock);
        PaidLeaveRequest rejected = request.reject(requester.employeeId(), comment, at);

        requests.update(rejected, expectedVersion);
        record(rejected, Optional.of(LeaveRequestStatus.SUBMITTED),
                LeaveRequestEventKind.REJECT, requester.employeeId(),
                Optional.of(comment), at);
        return resultOf(rejected);
    }

    /**
     * 本人が取り下げる（BR-16）。
     *
     * <p><strong>申請中の取下げは対象月の状態を問わない。</strong>
     * 残日数も月次清算も動かさないので拒否する理由が無く、拒否すると
     * 決裁されないまま取得日と月末が過ぎた申請が
     * どの状態にも遷移できなくなる（落とし穴 93）。
     */
    @Transactional
    public LeaveDecisionResult cancel(Requester requester, PaidLeaveRequestId id,
                                      long expectedVersion) {
        PaidLeaveRequest request = requestOf(id);
        // ★ 本人かどうかを最初に見る。あとに置くと、他人が呼んだときに
        //   not-the-requester ではなく month-already-closed が返り、
        //   その社員の対象月の状態が読み取れる
        if (!requester.isSelf(request.employeeId())) {
            throw new NotTheRequesterException(request.employeeId());
        }
        boolean wasApproved = request.status() == LeaveRequestStatus.APPROVED;
        if (wasApproved) {
            requireMonthEditable(request.employeeId(), YearMonth.from(request.leaveDate()));
        }

        LocalDateTime at = LocalDateTime.now(clock);
        PaidLeaveRequest canceled = request.cancel(requester.employeeId(),
                LocalDate.now(clock), at);
        requests.update(canceled, expectedVersion);
        record(canceled, Optional.of(request.status()), LeaveRequestEventKind.CANCEL,
                requester.employeeId(), Optional.empty(), at);

        // ★ 副作用を伴うのは承認済みからの取消だけである（落とし穴 95）。
        //   申請中の取下げでは所定総が変わっていないので、戻す理由が無い
        if (wasApproved) {
            applySideEffects(request, requester, id);
        }
        return resultOf(canceled);
    }

    /**
     * 取得日の当日以降に人事が取り消す（BR-16）。
     *
     * <p>訂正申請（BR-09）が動かせるのは打刻だけで、年休の状態は動かせない。
     * この経路が無いと、予定を変えて出勤した社員は
     * <strong>年休を 1 日消費したままその日も働く</strong>ことになる。
     */
    @Transactional
    public LeaveDecisionResult revoke(Requester requester, PaidLeaveRequestId id,
                                      String comment, long expectedVersion) {
        if (!requester.has(Role.HR)) {
            throw new AccessDeniedException();
        }
        PaidLeaveRequest request = requestOf(id);
        // ★ 取消も決裁である。承認・却下と同じく本人を弾く。
        //   理由つきで REVOKE として 5 年残る証跡なので、決裁者と対象社員が
        //   同一だと「誰も見ていない取消が人事の取消として記録される」（落とし穴 171）
        requireNotSelf(requester, request);
        requireMonthEditable(request.employeeId(), YearMonth.from(request.leaveDate()));

        LocalDateTime at = LocalDateTime.now(clock);
        PaidLeaveRequest revoked = request.revoke(requester.employeeId(), comment,
                LocalDate.now(clock), at);
        requests.update(revoked, expectedVersion);
        record(revoked, Optional.of(LeaveRequestStatus.APPROVED),
                LeaveRequestEventKind.REVOKE, requester.employeeId(),
                Optional.of(comment), at);
        applySideEffects(request, requester, id);
        return resultOf(revoked);
    }

    /**
     * その社員の申請。<strong>見てよいものだけ返す。</strong>
     *
     * <p>基準日は<strong>取得日</strong>にそろえる（訂正申請と同じ形）。
     * 今日の組織で決めると、異動した部下の異動前の申請を旧上長が見られなくなる。
     */
    @Transactional(readOnly = true)
    public List<PaidLeaveRequest> requestsOf(Requester requester, EmployeeId employeeId) {
        return requests.findByEmployee(employeeId).stream()
                .filter(request -> visibility.canView(requester, employeeId,
                        request.leaveDate()))
                .toList();
    }

    @Transactional(readOnly = true)
    public PaidLeaveRequest find(Requester requester, PaidLeaveRequestId id) {
        PaidLeaveRequest request = requestOf(id);
        if (!visibility.canView(requester, request.employeeId(), request.leaveDate())) {
            throw new AccessDeniedException();
        }
        return request;
    }

    /**
     * 承認待ちの一覧。<strong>閲覧できる社員に絞る</strong>（要件 4.1）。
     *
     * <p>絞りをアプリケーション層に置くのは、訂正申請（05）と同じ形にそろえるため。
     * 「配下部署か」は組織と基準日に依存するので、
     * SQL に写すと組織の解決を 2 か所に持つことになる。
     */
    @Transactional(readOnly = true)
    public List<PaidLeaveRequest> pendingFor(Requester requester) {
        return requests.findPending().stream()
                .filter(request -> visibility.canView(requester, request.employeeId(),
                        request.leaveDate()))
                .toList();
    }

    /**
     * 遷移の結果を組み立てる。
     *
     * <p><strong>版は読み直す。</strong> 更新は SQL 側で 1 つ進めるので、
     * 手元の集約は古い版を持ったままである。画面はこの版で次の操作を出すので、
     * 返さないと必ず 1 回 {@code 409} を踏む。
     */
    private LeaveDecisionResult resultOf(PaidLeaveRequest request) {
        return new LeaveDecisionResult(request, requests.currentVersion(request.id()),
                allocatedGrantDateOf(request),
                monthlyAttendances.stateOf(request.employeeId(),
                        YearMonth.from(request.leaveDate())));
    }

    /**
     * どの付与から消化したかの<strong>付与日</strong>。
     *
     * <p>識別子ではなく日付を返す。<strong>失効時期に直結する</strong>ので、
     * 「残 3 日」とだけ示されても、それが今月末に失効するのかは分からない。
     */
    private Optional<LocalDate> allocatedGrantDateOf(PaidLeaveRequest request) {
        return request.grantId().flatMap(grantId ->
                grants.findAll(request.employeeId()).stream()
                        .filter(grant -> grant.id().equals(grantId))
                        .map(PaidLeaveGrant::grantedOn)
                        .findFirst());
    }

    /**
     * 月次清算を計算し直し、提出済みの月を下書きへ戻す。
     *
     * <p>年休を取得した日は所定労働日数から除かれる（BR-05）ので、
     * <strong>承認も取消も月次清算を変える。</strong>
     * 再計算しないと、取り消した年休の日が所定総から除かれたままになり、
     * 不足時間が 8 時間ぶん過少に出る。
     */
    private void applySideEffects(PaidLeaveRequest request, Requester requester,
                                  PaidLeaveRequestId id) {
        YearMonth month = YearMonth.from(request.leaveDate());
        settlements.settle(request.employeeId(), month);
        monthlyAttendances.revertByLeave(request.employeeId(), month,
                requester.employeeId(), id.value());
    }

    /**
     * すでに実労働のある日は承認しない（BR-16）。
     *
     * <p>申請中の取下げに期限を設けない結果、未決裁のまま取得日を過ぎた申請が正当に残る。
     * そのまま承認できると、<strong>すでに働いた日</strong>の年休を後から通せてしまう。
     * その日は所定総から除かれるのに実労働もあるので、不足時間が過少に出る一方で
     * 社員は年休を 1 日失う（落とし穴 97）。
     */
    private void requireNotWorked(PaidLeaveRequest request) {
        boolean worked = dailyAttendances.find(request.employeeId(), request.leaveDate())
                .filter(DailyAttendance::hasWork)
                .isPresent();
        if (worked) {
            throw new LeaveDateAlreadyWorkedException(request.leaveDate());
        }
    }

    /** 残日数が足りるか（BR-16）。<strong>未処理の申請も差し引く。</strong> */
    private void requireEnoughLeave(Employee employee, LocalDate leaveDate) {
        // ★ 未処理の申請は 1 度だけ読む。呼ぶたびに引くと、同じ問い合わせが 3 回走る
        List<LocalDate> pending = balances.pendingDatesOf(employee.id());
        PaidLeaveBalance balance = balances.projectedBalanceOf(employee, LocalDate.now(clock));
        if (!balance.canAllocate(leaveDate, pending)) {
            throw new InsufficientPaidLeaveException(leaveDate, pending.size());
        }
    }

    private void requireWorkday(LocalDate leaveDate) {
        if (calendar.dayTypeOf(leaveDate) != DayType.WORKDAY) {
            throw new NotAWorkdayException(leaveDate);
        }
    }

    private void requireInService(Employee employee, LocalDate leaveDate) {
        if (!employee.isActiveOn(leaveDate)) {
            throw new LeaveDateNotInServiceException(leaveDate);
        }
    }

    private void requireNoActiveRequest(EmployeeId employeeId, LocalDate leaveDate) {
        boolean exists = requests.findByEmployee(employeeId).stream()
                .filter(PaidLeaveRequest::isActive)
                .anyMatch(request -> request.leaveDate().equals(leaveDate));
        if (exists) {
            throw new DuplicateLeaveRequestException(leaveDate);
        }
    }

    /**
     * 対象月が年休を動かせる状態か（BR-16）。
     *
     * <p>締め済みと承認済みを<strong>別のエラーにする。</strong>
     * 承認済みは承認を取り消せば直せるので、利用者への案内がまったく違う。
     */
    private void requireMonthEditable(EmployeeId employeeId, YearMonth month) {
        // ★ 判定はポートが持つ。訂正申請とまったく同じ規則なので、写すと片方が古くなる
        monthClosure.requireEditable(employeeId, month);
    }

    /**
     * 自分の申請ではないか（BR-11 の 4）。
     *
     * <p><strong>承認者の判定より先に置く。</strong>
     * {@code ApproverPolicy} は本人を承認者から外すので、あとに置くと
     * {@code not-approver} が返り、自己承認という事実が利用者に伝わらない。
     */
    private static void requireNotSelf(Requester requester, PaidLeaveRequest request) {
        if (requester.isSelf(request.employeeId())) {
            throw new SelfDecisionException(request.employeeId());
        }
    }

    private void requireApprover(Requester requester, EmployeeId employeeId,
                                 YearMonth month) {
        approverPolicy.requireApprover(requester, employeeId, month,
                LocalDate.now(clock));
    }

    /**
     * 却下してよいか。
     *
     * <p><strong>承認者が導出できない申請は、人事が却下できる。</strong>
     * 未来日の年休を申請したあとに退職が登録されると、その月には所属が無いので
     * 承認者が決まらない。本人は退職後ログインできないので取り下げられず、
     * <strong>どの状態にも遷移できない申請</strong>が部分一意インデックスを
     * 占有したまま残る（落とし穴 26・93）。
     *
     * <p><strong>承認は認めない。</strong> 在籍していない日の年休を通しても、
     * 残日数が減るだけで社員には何も残らない。開けるのは却下の側だけである。
     */
    private void requireRejecter(Requester requester, EmployeeId employeeId,
                                 YearMonth month) {
        Approver approver = approverPolicy.resolve(employeeId, month, LocalDate.now(clock));
        if (approver.isUnresolved() && requester.has(Role.HR)) {
            return;
        }
        if (!approver.isApprovedBy(requester.employeeId(), requester.has(Role.HR))) {
            throw new NotApproverException();
        }
    }

    private void record(PaidLeaveRequest request, Optional<LeaveRequestStatus> from,
                        LeaveRequestEventKind kind, EmployeeId actor,
                        Optional<String> comment, LocalDateTime at) {
        requests.appendEvent(LeaveRequestEvent.of(request.id(), from, request.status(),
                kind, actor, comment, at));
    }

    private Employee employeeOf(EmployeeId employeeId) {
        return employees.findById(employeeId)
                .orElseThrow(() -> new EmployeeNotFoundException(employeeId));
    }

    private PaidLeaveRequest requestOf(PaidLeaveRequestId id) {
        return requests.find(id).orElseThrow(() -> new LeaveRequestNotFoundException(id));
    }

    /**
     * 配分先が見つからない理由を分ける。
     *
     * <p>付与日がまだ到来していないのか、残日数が尽きたのかで案内が違う。
     * 申請の判定では到来予定の付与を仮に組み入れているので、前者が起きうる。
     */
    private RuntimeException grantMissing(PaidLeaveRequest request,
                                          PaidLeaveBalance balance) {
        boolean anyValid = balance.grants().stream()
                .anyMatch(grant -> grant.isValidOn(request.leaveDate()));
        return anyValid
                ? new InsufficientPaidLeaveException(request.leaveDate(), 0)
                : new GrantNotYetIssuedException(request.leaveDate());
    }
}
