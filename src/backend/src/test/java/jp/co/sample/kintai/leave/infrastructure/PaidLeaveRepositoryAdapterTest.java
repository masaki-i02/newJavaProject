package jp.co.sample.kintai.leave.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;

import jp.co.sample.kintai.leave.domain.AttendanceRate;
import jp.co.sample.kintai.leave.domain.GrantDecision;
import jp.co.sample.kintai.leave.domain.LeaveRequestEvent;
import jp.co.sample.kintai.leave.domain.LeaveRequestEventKind;
import jp.co.sample.kintai.leave.domain.LeaveRequestStatus;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrant;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrantId;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrantRepository;
import jp.co.sample.kintai.leave.domain.PaidLeaveRequest;
import jp.co.sample.kintai.leave.domain.PaidLeaveRequestId;
import jp.co.sample.kintai.leave.domain.PaidLeaveRequestRepository;
import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.PaidLeaveDays;
import jp.co.sample.kintai.support.Fixtures;
import jp.co.sample.kintai.support.IntegrationTestBase;

/** 年次有給休暇の永続化（IT-LV-99〜109）。 */
@DisplayName("年次有給休暇の永続化")
class PaidLeaveRepositoryAdapterTest extends IntegrationTestBase {

    @Autowired
    private PaidLeaveGrantRepository grants;

    @Autowired
    private PaidLeaveRequestRepository requests;

    @Autowired
    private PaidLeaveDays paidLeaveDays;

    private EmployeeId yamada;
    private EmployeeId manager;

    @BeforeEach
    void setUp() {
        var fixtures = new Fixtures(jdbc);
        yamada = new EmployeeId(fixtures.employee("E0031", LocalDate.of(2024, 4, 1)));
        manager = new EmployeeId(fixtures.employee("E0032", LocalDate.of(2020, 4, 1)));
    }

    @Nested
    @DisplayName("付与")
    class Grants {

        @Test
        @DisplayName("IT-LV-99 保存して読み戻すと同じ値になる")
        void roundTrip() {
            PaidLeaveGrant grant = granted(0, LocalDate.of(2024, 10, 1), 10);
            grants.save(grant);

            assertThat(grants.find(yamada, LocalDate.of(2024, 10, 1))).contains(grant);
        }

        /** 不付与の年も残す。「付与処理をしていない」と区別するため。 */
        @Test
        @DisplayName("IT-LV-100 不付与の付与も読み戻せる")
        void withheldRoundTrip() {
            PaidLeaveGrant grant = new PaidLeaveGrant(PaidLeaveGrantId.generate(), yamada, 1,
                    LocalDate.of(2025, 10, 1), AttendanceRate.of(245, 180),
                    new GrantDecision.Withheld(), LocalDateTime.of(2025, 10, 1, 0, 0), 1L);
            grants.save(grant);

            assertThat(grants.find(yamada, LocalDate.of(2025, 10, 1))).contains(grant);
        }

        /** 出勤扱いの日数と理由も残す。なぜ 8 割を満たしたのかを後から説明する。 */
        @Test
        @DisplayName("IT-LV-101 出勤扱いの日数と理由が読み戻せる")
        void deemedRoundTrip() {
            PaidLeaveGrant grant = new PaidLeaveGrant(PaidLeaveGrantId.generate(), yamada, 1,
                    LocalDate.of(2025, 10, 1),
                    new AttendanceRate(245, 150, 46, "産前産後休業"),
                    new GrantDecision.Granted(11), LocalDateTime.of(2025, 10, 1, 0, 0), 1L);
            grants.save(grant);

            assertThat(grants.find(yamada, LocalDate.of(2025, 10, 1)))
                    .get().extracting(PaidLeaveGrant::rate)
                    .isEqualTo(new AttendanceRate(245, 150, 46, "産前産後休業"));
        }

        /** 行の作成時に 1 を入れる。0 は「行が無い」ことだけを指す。 */
        @Test
        @DisplayName("IT-LV-102 新しい付与の版は 1 から始まる")
        void versionStartsAtOne() {
            grants.save(granted(0, LocalDate.of(2024, 10, 1), 10));

            assertThat(grants.find(yamada, LocalDate.of(2024, 10, 1)))
                    .get().extracting(PaidLeaveGrant::version).isEqualTo(1L);
        }

        @Test
        @DisplayName("IT-LV-103 古い版で再判定すると楽観ロックで拒否される")
        void staleVersion() {
            PaidLeaveGrant grant = granted(0, LocalDate.of(2024, 10, 1), 10);
            grants.save(grant);

            assertThatThrownBy(() -> grants.update(grant, 0L))
                    .isInstanceOf(OptimisticLockingFailureException.class);
        }

        /**
         * <strong>失効しているかどうかで絞らない。</strong>
         * 判定は {@code validPeriod()} が行う。SQL に写すと 1 日ずれる（落とし穴 91）。
         */
        @Test
        @DisplayName("IT-LV-104 失効した付与も読み出せる（絞るのはドメイン）")
        void expiredIsStillRead() {
            grants.save(granted(0, LocalDate.of(2020, 10, 1), 10));

            List<PaidLeaveGrant> found = grants.findAll(yamada);

            assertThat(found).hasSize(1);
            assertThat(found.getFirst().isValidOn(LocalDate.of(2026, 4, 1))).isFalse();
        }
    }

    @Nested
    @DisplayName("申請")
    class Requests {

        private PaidLeaveGrantId grantId;

        @BeforeEach
        void grantTen() {
            PaidLeaveGrant grant = granted(0, LocalDate.of(2024, 10, 1), 10);
            grants.save(grant);
            grantId = grant.id();
        }

        @Test
        @DisplayName("IT-LV-105 申請を保存して読み戻すと同じ値になる")
        void roundTrip() {
            PaidLeaveRequest request = submitted();
            requests.save(request);

            assertThat(requests.find(request.id())).contains(request);
        }

        /**
         * <strong>配分先は社員 ID とともに書く。</strong>
         * 複合外部キーが「他人の付与から自分の年休を消化する行」を拒む。
         */
        @Test
        @DisplayName("IT-LV-106 承認すると配分先が読み戻せる")
        void approvedRoundTrip() {
            PaidLeaveRequest request = submitted();
            requests.save(request);
            PaidLeaveRequest approved = request.approve(manager, grantId,
                    LocalDateTime.of(2026, 6, 2, 10, 0));
            requests.update(approved, 1L);

            assertThat(requests.find(request.id()))
                    .get().extracting(PaidLeaveRequest::grantId).isEqualTo(Optional.of(grantId));
        }

        /** 取消後も、誰がいつ承認したかを追える。 */
        @Test
        @DisplayName("IT-LV-107 取り消しても承認者が残り、配分は外れる")
        void canceledKeepsApprover() {
            PaidLeaveRequest request = submitted();
            requests.save(request);
            PaidLeaveRequest approved = request.approve(manager, grantId,
                    LocalDateTime.of(2026, 6, 2, 10, 0));
            requests.update(approved, 1L);
            requests.update(approved.cancel(yamada, LocalDate.of(2026, 6, 9),
                    LocalDateTime.of(2026, 6, 9, 10, 0)), 2L);

            PaidLeaveRequest reloaded = requests.find(request.id()).orElseThrow();
            assertThat(reloaded.status()).isEqualTo(LeaveRequestStatus.CANCELED);
            assertThat(reloaded.decidedBy()).contains(manager);
            assertThat(reloaded.canceledBy()).contains(yamada);
            assertThat(reloaded.grantId()).isEmpty();
        }

        @Test
        @DisplayName("IT-LV-108 古い版で更新すると楽観ロックで拒否される")
        void staleVersion() {
            PaidLeaveRequest request = submitted();
            requests.save(request);

            assertThatThrownBy(() -> requests.update(request, 0L))
                    .isInstanceOf(OptimisticLockingFailureException.class);
        }

        /** 新規申請の遷移元は「行が無かった」。自分自身への遷移にしない。 */
        @Test
        @DisplayName("IT-LV-109 新規申請の証跡は遷移元が NONE になる")
        void submitEvent() {
            PaidLeaveRequest request = submitted();
            requests.save(request);
            requests.appendEvent(LeaveRequestEvent.of(request.id(), Optional.empty(),
                    LeaveRequestStatus.SUBMITTED, LeaveRequestEventKind.SUBMIT, yamada,
                    Optional.empty(), LocalDateTime.of(2026, 6, 1, 9, 0)));

            assertThat(jdbc.queryForObject("""
                    SELECT from_status FROM paid_leave_request_events
                     WHERE paid_leave_request_id = ?
                    """, String.class, request.id().value())).isEqualTo("NONE");
        }

        @Test
        @DisplayName("IT-LV-110 承認済みの取得日だけを PaidLeaveDays が返す")
        void approvedDatesOnly() {
            PaidLeaveRequest pending = submitted();
            requests.save(pending);
            PaidLeaveRequest other = PaidLeaveRequest.submit(PaidLeaveRequestId.generate(),
                    yamada, yamada, LocalDate.of(2026, 6, 11), Optional.empty(),
                    LocalDateTime.of(2026, 6, 1, 9, 0));
            requests.save(other);
            requests.update(other.approve(manager, grantId,
                    LocalDateTime.of(2026, 6, 2, 10, 0)), 1L);

            var june = new DateRange(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1));

            assertThat(paidLeaveDays.approvedOn(yamada, june))
                    .containsExactly(LocalDate.of(2026, 6, 11));
        }

        /** 清算期間の外の年休は数えない。月中入社・月中退職で効く。 */
        @Test
        @DisplayName("IT-LV-111 期間の外にある取得日は返さない")
        void outsidePeriod() {
            PaidLeaveRequest request = submitted();
            requests.save(request);
            requests.update(request.approve(manager, grantId,
                    LocalDateTime.of(2026, 6, 2, 10, 0)), 1L);

            var july = new DateRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1));

            assertThat(paidLeaveDays.approvedOn(yamada, july)).isEmpty();
        }

        private PaidLeaveRequest submitted() {
            return PaidLeaveRequest.submit(PaidLeaveRequestId.generate(), yamada, yamada,
                    LocalDate.of(2026, 6, 10), Optional.of("私用のため"),
                    LocalDateTime.of(2026, 6, 1, 9, 0));
        }
    }

    private PaidLeaveGrant granted(int index, LocalDate grantedOn, int days) {
        return new PaidLeaveGrant(new PaidLeaveGrantId(UUID.randomUUID()), yamada, index,
                grantedOn, AttendanceRate.of(245, 245), new GrantDecision.Granted(days),
                grantedOn.atStartOfDay(), 1L);
    }
}
