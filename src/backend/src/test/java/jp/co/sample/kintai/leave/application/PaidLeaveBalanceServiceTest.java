package jp.co.sample.kintai.leave.application;

import static org.assertj.core.api.Assertions.assertThat;

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
     * 表示する日数と申請の受理判定を、同じ仮配分・同じ期間から導く（落とし穴 96）。
     *
     * <p>{@code availableDays} を到来済みの付与だけで数え、受理判定には
     * 到来予定の付与を含めていると、<strong>表示のほうが少なく出る。</strong>
     * 社員は権利を行使しない方向へ倒れる。
     *
     * <p>{@code remainingDays} は逆に<strong>実体化した付与だけ</strong>である。
     * 「いま何日持っているか」に未到来の付与を混ぜてはならない。
     */
    @Test
    @DisplayName("UT-LV-67 到来済みで未処理の付与を availableDays に数える")
    void unprocessedGrantsCountTowardAvailableDays() {
        // 2026-04-01 入社。0 回目の付与日 2026-10-01 は到来しているが、行はまだ無い。
        // 1 回目（2027-10-01・11 日）も申請できる期間（当日 + 1 年）に入る
        yamadaId = hire("E0001", "山田 太郎", LocalDate.of(2026, 4, 1));
        yamada = new Requester(yamadaId, Set.of(Role.EMPLOYEE));

        PaidLeaveSummary before = service.summaryOf(yamada, yamadaId, Optional.empty());
        assertThat(before.remainingDays()).as("実体化した付与はまだ無い").isZero();
        assertThat(before.availableDays())
                .as("申請できる期間に入る付与（10 日 + 11 日）を数える")
                .isEqualTo(21);

        requestService.submit(yamada, yamadaId, LocalDate.of(2026, 11, 16),
                Optional.empty());

        PaidLeaveSummary after = service.summaryOf(yamada, yamadaId, Optional.empty());
        assertThat(after.remainingDays()).as("未処理の申請は保有日数を変えない").isZero();
        assertThat(after.availableDays())
                .as("未処理の申請を仮に配分して 1 日減る")
                .isEqualTo(20);
    }

    /**
     * <strong>付与日そのものが到来していない付与も `availableDays` に数える</strong>（BR-16）。
     *
     * <p>UT-LV-67 は「付与日は到来したが行がまだ無い」場合で、
     * こちらは<strong>付与日が未来にある</strong>場合である。閾値の反対側にあたる。
     *
     * <p>申請の受理判定は取得日の時点で有効な付与を探すので、
     * 次の付与日より後の取得日は受け付けられる。表示だけが基準日で絞ると、
     * <strong>「0 日と表示されるのに申請は通る」</strong>という食い違いが起きる。
     * 社員は表示を見て権利を行使しない方向へ倒れる（落とし穴 96）。
     */
    @Test
    @DisplayName("UT-LV-73 付与日が未到来でも availableDays に数える")
    void futureGrantDatesCountTowardAvailableDays() {
        // 2026-08-01 入社 → 0 回目の付与日は 2027-02-01。今日（2026-11-10）にはまだ到来しない
        yamadaId = hire("E0001", "山田 太郎", LocalDate.of(2026, 8, 1));
        yamada = new Requester(yamadaId, Set.of(Role.EMPLOYEE));
        LocalDate afterGrant = LocalDate.of(2027, 2, 15);

        PaidLeaveSummary summary = service.summaryOf(yamada, yamadaId, Optional.empty());
        requestService.submit(yamada, yamadaId, afterGrant, Optional.empty());

        assertThat(summary.remainingDays())
                .as("今日の時点で保有している日数は 0 である")
                .isZero();
        assertThat(summary.availableDays())
                .as("受理される日数と一致しなければならない")
                .isEqualTo(10);
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
        // ★ 戻り値の status を見ても恒真である（approve はリテラルで APPROVED を詰めて返す）。
        //   往復して読み戻したものを見る（落とし穴 36）
        assertThat(requests.find(request.id()).orElseThrow().status())
                .isEqualTo(LeaveRequestStatus.APPROVED);
    }

    private EmployeeId hire(String number, String name, LocalDate hiredOn) {
        var id = new EmployeeId(UUID.randomUUID());
        employees.save(new Employee(id, new EmployeeNumber(number), name,
                new Email(number.toLowerCase() + "@example.com"), hiredOn,
                Optional.empty(), Set.of(Role.EMPLOYEE)));
        return id;
    }
}
