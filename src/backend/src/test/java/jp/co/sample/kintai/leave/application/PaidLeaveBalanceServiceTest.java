package jp.co.sample.kintai.leave.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.convention.TestBean;

import jp.co.sample.kintai.employee.domain.Email;
import jp.co.sample.kintai.employee.domain.Employee;
import jp.co.sample.kintai.employee.domain.EmployeeNumber;
import jp.co.sample.kintai.employee.domain.EmployeeRepository;
import jp.co.sample.kintai.leave.domain.AnnualObligation;
import jp.co.sample.kintai.leave.domain.AttendanceRate;
import jp.co.sample.kintai.leave.domain.GrantDecision;
import jp.co.sample.kintai.leave.domain.LeaveRequestStatus;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrant;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrantId;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrantRepository;
import jp.co.sample.kintai.leave.domain.PaidLeaveRequest;
import jp.co.sample.kintai.leave.domain.PaidLeaveRequestId;
import jp.co.sample.kintai.leave.domain.PaidLeaveRequestRepository;
import jp.co.sample.kintai.shared.domain.BusinessZone;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.Requester;
import jp.co.sample.kintai.shared.domain.Role;
import jp.co.sample.kintai.support.IntegrationTestBase;

/**
 * 残日数と年 5 日の取得義務の参照（BR-15 / BR-17）。
 *
 * <p>残日数の計算そのものは {@code PaidLeaveBalanceTest} がドメインに直接あてている。
 * ここで見るのは<strong>複数のリポジトリを束ねた結果</strong>である。
 */
@DisplayName("残日数の照会")
class PaidLeaveBalanceServiceTest extends IntegrationTestBase {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    @TestBean
    private Clock clock;

    static Clock clock() {
        return Clock.fixed(TODAY.atTime(10, 0).atZone(BusinessZone.ID).toInstant(),
                BusinessZone.ID);
    }

    @Autowired
    private PaidLeaveBalanceService service;
    @Autowired
    private PaidLeaveRequestService requestService;
    @Autowired
    private PaidLeaveGrantRepository grants;
    @Autowired
    private PaidLeaveRequestRepository requests;
    @Autowired
    private EmployeeRepository employees;

    private EmployeeId yamadaId;
    private EmployeeId managerId;
    private Requester yamada;

    @BeforeEach
    void setUpEmployees() {
        managerId = hire("E0100", "課長 次郎", LocalDate.of(2020, 4, 1));
    }

    /**
     * 年 5 日の取得義務は<strong>取得した日</strong>で数える（BR-17）。
     *
     * <p>どの付与から消化したかは問わない。配分先で絞ると、
     * <strong>前年の繰越を使った日が数から漏れる。</strong>
     * 先入先出（BR-15）は古い付与から消すので、新しい付与を受けた直後に取った年休は
     * ほぼ必ず前年の付与へ配分される。絞ってしまうと、
     * その年の義務がいつまでも 0 日のままになる。
     */
    @Test
    @DisplayName("UT-LV-41 前年の繰越から消化した日も、その年の取得義務に数える")
    void obligationCountsRegardlessOfAllocation() {
        // 2025-04-01 入社 → 0 回目 2025-10-01（10 日）／ 1 回目 2026-10-01（11 日）
        yamadaId = hire("E0001", "山田 太郎", LocalDate.of(2025, 4, 1));
        yamada = new Requester(yamadaId, Set.of(Role.EMPLOYEE));
        PaidLeaveGrantId first = grant(0, LocalDate.of(2025, 10, 1), 10);
        PaidLeaveGrantId second = grant(1, LocalDate.of(2026, 10, 1), 11);

        // 取得日は 1 回目の義務期間 [2026-10-01, 2027-10-01) の中。
        // 先入先出なので配分先は 0 回目の付与になる
        LocalDate leaveDate = LocalDate.of(2026, 10, 5);
        approvedLeaveOn(leaveDate);

        PaidLeaveSummary summary = service.summaryOf(yamada, yamadaId, Optional.empty());

        assertThat(requests.findByEmployee(yamadaId))
                .singleElement()
                .extracting(request -> request.allocation().orElseThrow().grantId())
                .as("先入先出なので配分先は前年の付与である")
                .isEqualTo(first);
        assertThat(summary.obligations())
                .filteredOn(obligation -> obligation.grantId().equals(second))
                .singleElement()
                .extracting(AnnualObligation::takenDays)
                .as("配分先が前年でも、その年の取得義務には数える")
                .isEqualTo(1);
    }

    /**
     * 表示する日数と申請の受理判定を、同じ仮配分から導く（落とし穴 96）。
     *
     * <p>{@code availableDays} を到来済みの付与だけで数え、受理判定には
     * 到来予定の付与を含めていると、<strong>表示のほうが少なく出る。</strong>
     * 社員は権利を行使しない方向へ倒れる。
     *
     * <p>{@code remainingDays} は逆に<strong>実体化した付与だけ</strong>である。
     * 「いま何日持っているか」に未到来の付与を混ぜてはならない。
     */
    @Test
    @DisplayName("UT-LV-67 未到来の付与は availableDays に数え、remainingDays には数えない")
    void scheduledGrantsCountTowardAvailableDays() {
        // 2026-04-01 入社。0 回目の付与日 2026-10-01 は到来しているが、行はまだ無い
        yamadaId = hire("E0001", "山田 太郎", LocalDate.of(2026, 4, 1));
        yamada = new Requester(yamadaId, Set.of(Role.EMPLOYEE));

        PaidLeaveSummary before = service.summaryOf(yamada, yamadaId, Optional.empty());
        assertThat(before.remainingDays()).as("実体化した付与はまだ無い").isZero();
        assertThat(before.availableDays()).as("到来予定の 10 日を数える").isEqualTo(10);

        LocalDate date = LocalDate.of(2026, 11, 16);
        for (int i = 0; i < 10; i++) {
            requestService.submit(yamada, yamadaId, date, Optional.empty());
            date = date.plusDays(1);
        }

        PaidLeaveSummary after = service.summaryOf(yamada, yamadaId, Optional.empty());
        assertThat(after.availableDays()).as("未処理の申請を仮に配分して 0 になる").isZero();
        LocalDate eleventh = date;
        assertThatThrownBy(() -> requestService.submit(yamada, yamadaId, eleventh,
                Optional.empty()))
                .as("表示が 0 の日数と、受理を拒む日数が一致する")
                .isInstanceOf(InsufficientPaidLeaveException.class);
    }

    private PaidLeaveGrantId grant(int index, LocalDate grantedOn, int days) {
        PaidLeaveGrantId id = PaidLeaveGrantId.generate();
        grants.save(new PaidLeaveGrant(id, yamadaId, index, grantedOn,
                AttendanceRate.of(200, 200), new GrantDecision.Granted(days),
                grantedOn.atStartOfDay(), 1L));
        return id;
    }

    /**
     * 承認済みの年休を 1 件作る。
     *
     * <p><strong>配分先はドメインに決めさせる。</strong>
     * テスト側で付与を指定すると、先入先出の規則を代役が持つことになり、
     * このテストは配分の実装を 1 行も検査しない（落とし穴 37）。
     */
    private void approvedLeaveOn(LocalDate leaveDate) {
        PaidLeaveRequest request = PaidLeaveRequest.submit(PaidLeaveRequestId.generate(),
                yamadaId, yamadaId, leaveDate, Optional.empty(),
                leaveDate.minusDays(7).atTime(9, 0));
        requests.save(request);
        PaidLeaveGrantId allocatedTo = service.actualBalanceOf(yamadaId)
                .allocationFor(leaveDate).orElseThrow();
        PaidLeaveRequest approved = request.approve(managerId, allocatedTo,
                leaveDate.minusDays(6).atTime(9, 0));
        requests.update(approved, request.version());
        assertThat(approved.status()).isEqualTo(LeaveRequestStatus.APPROVED);
    }

    private EmployeeId hire(String number, String name, LocalDate hiredOn) {
        var id = new EmployeeId(UUID.randomUUID());
        employees.save(new Employee(id, new EmployeeNumber(number), name,
                new Email(number.toLowerCase() + "@example.com"), hiredOn,
                Optional.empty(), Set.of(Role.EMPLOYEE)));
        return id;
    }
}
