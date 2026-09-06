package jp.co.sample.kintai.leave.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sample.kintai.employee.domain.Employee;
import jp.co.sample.kintai.employee.domain.EmployeeRepository;
import jp.co.sample.kintai.leave.domain.AnnualObligation;
import jp.co.sample.kintai.leave.domain.AttendanceRate;
import jp.co.sample.kintai.leave.domain.GrantDecision;
import jp.co.sample.kintai.leave.domain.GrantSchedule;
import jp.co.sample.kintai.leave.domain.LeaveAllocation;
import jp.co.sample.kintai.leave.domain.LeaveRequestStatus;
import jp.co.sample.kintai.leave.domain.PaidLeaveBalance;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrant;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrantId;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrantRepository;
import jp.co.sample.kintai.leave.domain.PaidLeaveRequest;
import jp.co.sample.kintai.leave.domain.PaidLeaveRequestRepository;
import jp.co.sample.kintai.shared.application.AccessDeniedException;
import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.EmployeeVisibility;
import jp.co.sample.kintai.shared.domain.Requester;

/**
 * 残日数と年 5 日の取得義務の参照（BR-15 / BR-17）。
 *
 * <p><strong>残日数は列を持たず、付与と配分から導く</strong>（ADR 0006）。
 */
@Service
public class PaidLeaveBalanceService {

    /** 申請できるのは当日から何年先までか（API の共通仕様 1.2）。 */
    private static final int REQUESTABLE_YEARS = 1;

    private final PaidLeaveGrantRepository grants;
    private final PaidLeaveRequestRepository requests;
    private final EmployeeRepository employees;
    private final EmployeeVisibility visibility;
    private final Clock clock;

    public PaidLeaveBalanceService(PaidLeaveGrantRepository grants,
                                   PaidLeaveRequestRepository requests,
                                   EmployeeRepository employees,
                                   EmployeeVisibility visibility,
                                   Clock clock) {
        this.grants = grants;
        this.requests = requests;
        this.employees = employees;
        this.visibility = visibility;
        this.clock = clock;
    }

    /**
     * 残日数と付与の内訳。
     *
     * <p>閲覧範囲は {@link EmployeeVisibility} が判定する。
     * 「配下部署か」は組織と基準日に依存するので、Spring Security の設定には置けない。
     */
    @Transactional(readOnly = true)
    public PaidLeaveSummary summaryOf(Requester requester, EmployeeId employeeId,
                                      Optional<LocalDate> asOf) {
        requireVisible(requester, employeeId);
        LocalDate date = asOf.orElseGet(() -> LocalDate.now(clock));
        Employee employee = employees.findById(employeeId)
                .orElseThrow(() -> new EmployeeNotFoundException(employeeId));

        List<PaidLeaveGrant> all = grants.findAll(employeeId);
        List<PaidLeaveRequest> requested = requests.findByEmployee(employeeId);

        PaidLeaveBalance actual = new PaidLeaveBalance(all, allocationsOf(requested));
        // ★ 表示する availableDays と申請の受理判定は、同じ仮配分・同じ期間から導く。
        //   別の式にすると「残 3 日と表示されたのに拒否される」あるいはその逆が起きる（落とし穴 96）。
        //   窓の起点は asOf ではなく当日である。受理判定（submit）が当日で見るので、
        //   asOf を指定した照会だけ窓がずれると、表示と受理がまた食い違う（落とし穴 103）
        DateRange requestable = requestableWindow(LocalDate.now(clock));
        PaidLeaveBalance projected = new PaidLeaveBalance(
                withScheduled(all, employee, requestable), allocationsOf(requested));

        return new PaidLeaveSummary(employeeId, date,
                actual.remainingDays(date),
                projected.availableDays(requestable, pendingDatesOf(requested)),
                all, actual.remainingByGrant(), obligationsOf(all, requested, date));
    }

    /**
     * 年 5 日の取得義務が未達の社員（BR-17）。
     *
     * <p><strong>閲覧範囲で絞る。</strong> 絞らないと、一般の承認者が
     * 配下でない社員の年休の取得状況を見られる（要件 4.1）。
     * 絞りを SQL に写さないのは、「配下部署か」が組織と基準日に依存するからである
     * （訂正申請・年休の承認待ち一覧と同じ形）。
     *
     * <p><strong>基準日はその義務期間に含まれるかで判定する。</strong>
     * 「いま進行中の 1 年」を対象にするので、
     * 付与日から 1 年を過ぎた義務（もう是正できないもの）は出さない。
     *
     * @param onlyShortfall 未達の社員だけに絞るか。{@code false} なら充足も返す
     */
    @Transactional(readOnly = true)
    public ObligationList obligationsFor(Requester requester, Optional<LocalDate> asOf,
                                         boolean onlyShortfall) {
        LocalDate date = asOf.orElseGet(() -> LocalDate.now(clock));
        List<ObligationSummary> result = new ArrayList<>();
        // ★ 閲覧範囲の基準日は当日にそろえる（summaryOf と同じ）。
        //   利用者が送った asOf を認可の基準にすると、過去の日付を送るだけで
        //   当時の配下の取得状況を引ける。asOf は「いつ時点の義務か」だけに使う
        LocalDate today = LocalDate.now(clock);
        for (Employee employee : employees.findForDirectory(date, false)) {
            if (!visibility.canView(requester, employee.id(), today)) {
                continue;
            }
            List<PaidLeaveRequest> requested = requests.findByEmployee(employee.id());
            for (AnnualObligation obligation
                    : obligationsOf(grants.findAll(employee.id()), requested, date)) {
                if (!obligation.period().contains(date)) {
                    continue;
                }
                if (onlyShortfall && obligation.isFulfilled()) {
                    continue;
                }
                // ★ 数える先は deadline（閉区間の最終日）である。
                //   period().toExclusive() まで数えると 1 日多くなる。
                //   expiresOn と deadline で区間の扱いが違うので、必ず取り違える（落とし穴 10）
                result.add(new ObligationSummary(employee.id(), obligation,
                        (int) ChronoUnit.DAYS.between(date, obligation.deadline())));
            }
        }
        return new ObligationList(date, result);
    }

    /**
     * 申請の受理判定に使う残日数。
     *
     * <p><strong>到来予定の付与を仮に組み入れる。</strong>
     * 付与の行は到来したぶんしか作られないので、そのままだと
     * 次の付与日の直後の日程を付与日が来るまで誰も申請できない。
     * 時季指定は労働者の権利（39 条 5 項）であり、システムの都合で妨げてはならない。
     */
    PaidLeaveBalance projectedBalanceOf(Employee employee, LocalDate asOf) {
        List<PaidLeaveRequest> requested = requests.findByEmployee(employee.id());
        return new PaidLeaveBalance(withScheduled(grants.findAll(employee.id()), employee,
                requestableWindow(asOf)), allocationsOf(requested));
    }

    /** 承認時の配分に使う残日数。<strong>実体化した付与だけ</strong>を配分先にする。 */
    PaidLeaveBalance actualBalanceOf(EmployeeId employeeId) {
        return new PaidLeaveBalance(grants.findAll(employeeId),
                allocationsOf(requests.findByEmployee(employeeId)));
    }

    /**
     * 申請できる取得日の範囲（API の共通仕様 1.2）。
     *
     * <p><strong>1 か所で決める。</strong> 到来予定の付与をどこまで組み入れるかと、
     * {@code availableDays} がどの付与を数えるかは同じ範囲でなければならない。
     * 別々に書くと、組み入れた付与が表示から落ちる（落とし穴 96）。
     */
    private static DateRange requestableWindow(LocalDate asOf) {
        return new DateRange(asOf, asOf.plusYears(REQUESTABLE_YEARS));
    }

    /** 未処理の申請の取得日。取得日が過ぎたものも含める（本人がいつでも取り下げられる）。 */
    List<LocalDate> pendingDatesOf(List<PaidLeaveRequest> requested) {
        return requested.stream()
                .filter(request -> request.status() == LeaveRequestStatus.SUBMITTED)
                .map(PaidLeaveRequest::leaveDate)
                .toList();
    }

    List<LocalDate> pendingDatesOf(EmployeeId employeeId) {
        return pendingDatesOf(requests.findByEmployee(employeeId));
    }

    /**
     * 実体化した付与に、到来予定の付与を仮に足す。
     *
     * <p>出勤率は未判定なので、<strong>満たすものとして数える。</strong>
     * 8 割未達で {@code Withheld} になった場合は、承認時の残日数の再検査が拒む。
     */
    private List<PaidLeaveGrant> withScheduled(List<PaidLeaveGrant> materialized,
                                               Employee employee, DateRange requestable) {
        var schedule = new GrantSchedule(employee.hiredOn());
        int nextIndex = materialized.stream()
                .mapToInt(PaidLeaveGrant::grantIndex).max().orElse(-1) + 1;
        List<PaidLeaveGrant> result = new ArrayList<>(materialized);
        for (int index = nextIndex;
                schedule.grantDateOf(index).isBefore(requestable.toExclusive()); index++) {
            LocalDate grantedOn = schedule.grantDateOf(index);
            if (!employee.isActiveOn(grantedOn)) {
                break;
            }
            result.add(new PaidLeaveGrant(PaidLeaveGrantId.generate(), employee.id(), index,
                    grantedOn, AttendanceRate.of(0, 0),
                    new GrantDecision.Granted(schedule.daysOf(index)),
                    grantedOn.atStartOfDay(), 0L));
        }
        return List.copyOf(result);
    }

    private static List<LeaveAllocation> allocationsOf(List<PaidLeaveRequest> requested) {
        return requested.stream()
                .map(PaidLeaveRequest::allocation)
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * 年 5 日の取得義務（BR-17）。
     *
     * <p><strong>どの付与から消化したかは問わない。</strong>
     * 「その期間中に取得した日数」を数える。配分先で絞ると、
     * 前年の繰越を使った日が数から漏れる。
     */
    private List<AnnualObligation> obligationsOf(List<PaidLeaveGrant> all,
                                                 List<PaidLeaveRequest> requested,
                                                 LocalDate asOf) {
        List<LocalDate> taken = requested.stream()
                .filter(request -> request.status() == LeaveRequestStatus.APPROVED)
                .map(PaidLeaveRequest::leaveDate)
                .toList();
        return all.stream()
                .filter(grant -> grant.days() >= AnnualObligation.TARGET_GRANT_DAYS)
                .filter(grant -> !grant.grantedOn().isAfter(asOf))
                .map(grant -> {
                    var obligation = new AnnualObligation(grant.id(), grant.grantedOn(), 0);
                    int count = (int) taken.stream()
                            .filter(date -> obligation.period().contains(date))
                            .count();
                    return new AnnualObligation(grant.id(), grant.grantedOn(), count);
                })
                .sorted(Comparator.comparing(AnnualObligation::grantedOn).reversed())
                .toList();
    }

    private void requireVisible(Requester requester, EmployeeId employeeId) {
        if (!visibility.canView(requester, employeeId, LocalDate.now(clock))) {
            throw new AccessDeniedException();
        }
    }
}
