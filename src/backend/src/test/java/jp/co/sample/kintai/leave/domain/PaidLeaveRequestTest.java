package jp.co.sample.kintai.leave.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * 年休の申請の状態遷移（BR-16）。
 *
 * <p><strong>集約に直接あてる。</strong>
 * 自己承認の禁止は {@code ApproverPolicy} の承認者判定で先に弾かれるため、
 * アプリケーション層を通すテストからは一度も働かない（CLAUDE.md 落とし穴 58）。
 */
@DisplayName("年次有給休暇の申請")
class PaidLeaveRequestTest {

    private static final EmployeeId YAMADA = new EmployeeId(UUID.randomUUID());
    private static final EmployeeId MANAGER = new EmployeeId(UUID.randomUUID());
    private static final EmployeeId HR = new EmployeeId(UUID.randomUUID());
    private static final LocalDate LEAVE_DATE = LocalDate.of(2026, 6, 10);
    private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 6, 1, 9, 0);
    private static final PaidLeaveGrantId GRANT = PaidLeaveGrantId.generate();

    @Nested
    @DisplayName("申請")
    class Submit {

        @Test
        @DisplayName("UT-LV-36 本人以外は申請できない（人事の代理も認めない）")
        void proxyRejected() {
            assertThatThrownBy(() -> PaidLeaveRequest.submit(PaidLeaveRequestId.generate(),
                    HR, YAMADA, LEAVE_DATE, Optional.empty(), REQUESTED_AT))
                    .isInstanceOf(NotTheRequesterException.class);
        }

        /** 版は行の作成時に 1 から始める（05 と同じ約束）。 */
        @Test
        @DisplayName("新しい申請の版は 1 から始まる")
        void versionStartsAtOne() {
            assertThat(submitted().version()).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("承認と却下")
    class Decision {

        /**
         * <strong>集約が承認者の判定より先に自己承認を弾く。</strong>
         * アプリケーション層を通すと {@code not-approver} が先に返り、
         * この検査は一度も働かない。
         */
        @Test
        @DisplayName("UT-LV-30 自分の申請を自分で承認できない")
        void selfApproval() {
            assertThatThrownBy(() -> submitted().approve(YAMADA, GRANT, REQUESTED_AT))
                    .isInstanceOf(SelfDecisionException.class);
        }

        @Test
        @DisplayName("自分の申請を自分で却下できない")
        void selfRejection() {
            assertThatThrownBy(() -> submitted().reject(YAMADA, "だめ", REQUESTED_AT))
                    .isInstanceOf(SelfDecisionException.class);
        }

        @Test
        @DisplayName("UT-LV-37 承認すると先入先出の配分先が確定する")
        void allocates() {
            PaidLeaveRequest approved = submitted().approve(MANAGER, GRANT, REQUESTED_AT);

            assertThat(approved.status()).isEqualTo(LeaveRequestStatus.APPROVED);
            assertThat(approved.grantId()).contains(GRANT);
            assertThat(approved.allocation())
                    .contains(new LeaveAllocation(GRANT, LEAVE_DATE));
        }

        @Test
        @DisplayName("UT-LV-31 却下に理由が無ければ生成できない")
        void rejectionNeedsComment() {
            assertThatThrownBy(() -> submitted().reject(MANAGER, "   ", REQUESTED_AT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("理由");
        }

        @Test
        @DisplayName("UT-LV-32 処理済みの申請を再び承認できない")
        void alreadyDecided() {
            PaidLeaveRequest approved = submitted().approve(MANAGER, GRANT, REQUESTED_AT);

            assertThatThrownBy(() -> approved.approve(MANAGER, GRANT, REQUESTED_AT))
                    .isInstanceOf(AlreadyDecidedException.class);
        }
    }

    @Nested
    @DisplayName("取下げと取消")
    class Cancellation {

        @Test
        @DisplayName("UT-LV-33 承認済みは取得日の前日まで本人が取り消せる")
        void dayBefore() {
            PaidLeaveRequest approved = submitted().approve(MANAGER, GRANT, REQUESTED_AT);
            PaidLeaveRequest canceled = approved.cancel(YAMADA, LEAVE_DATE.minusDays(1),
                    LEAVE_DATE.minusDays(1).atTime(18, 0));

            assertThat(canceled.status()).isEqualTo(LeaveRequestStatus.CANCELED);
            // ★ 配分を外す。残ったままだと残日数が戻らない
            assertThat(canceled.grantId()).isEmpty();
            assertThat(canceled.canceledBy()).contains(YAMADA);
        }

        /** {@code !today.isAfter(leaveDate)} と書くと当日まで取り消せてしまう。 */
        @Test
        @DisplayName("UT-LV-34 承認済みは取得日の当日には本人が取り消せない")
        void onTheDay() {
            PaidLeaveRequest approved = submitted().approve(MANAGER, GRANT, REQUESTED_AT);

            assertThatThrownBy(() -> approved.cancel(YAMADA, LEAVE_DATE,
                    LEAVE_DATE.atTime(9, 0)))
                    .isInstanceOf(NotCancelableException.class);
        }

        /**
         * <strong>「取得日の前日まで」は承認済みの取消に効く期限である。</strong>
         * 申請中にも当てると、承認者が決裁しないまま取得日と月末が過ぎた申請が
         * どの状態にも遷移できなくなる（落とし穴 93）。
         */
        @Test
        @DisplayName("UT-LV-54 申請中は取得日を過ぎても本人が取り下げられる")
        void submittedHasNoDeadline() {
            PaidLeaveRequest canceled = submitted().cancel(YAMADA, LEAVE_DATE.plusMonths(2),
                    LEAVE_DATE.plusMonths(2).atTime(9, 0));

            assertThat(canceled.status()).isEqualTo(LeaveRequestStatus.CANCELED);
        }

        @Test
        @DisplayName("UT-LV-35 承認済みを取り消すと配分が外れる")
        void releasesAllocation() {
            PaidLeaveRequest canceled = submitted()
                    .approve(MANAGER, GRANT, REQUESTED_AT)
                    .cancel(YAMADA, LEAVE_DATE.minusDays(1), REQUESTED_AT);

            assertThat(canceled.allocation()).isEmpty();
        }

        /** 承認済みだった申請を取り消しても、誰がいつ承認したかは残す。 */
        @Test
        @DisplayName("取消後も承認者が残る")
        void keepsApprover() {
            PaidLeaveRequest canceled = submitted()
                    .approve(MANAGER, GRANT, REQUESTED_AT)
                    .cancel(YAMADA, LEAVE_DATE.minusDays(1), REQUESTED_AT);

            assertThat(canceled.decidedBy()).contains(MANAGER);
        }

        @Test
        @DisplayName("UT-LV-36 他人の申請は取り下げられない")
        void notTheRequester() {
            assertThatThrownBy(() -> submitted().cancel(MANAGER, LEAVE_DATE.minusDays(1),
                    REQUESTED_AT))
                    .isInstanceOf(NotTheRequesterException.class);
        }

        /**
         * 訂正申請（BR-09）が動かせるのは打刻だけで、年休の状態は動かせない。
         * この経路が無いと<strong>年休を 1 日消費したままその日も働く</strong>ことになる。
         */
        @Test
        @DisplayName("UT-LV-55 取得日の当日以降は人事が理由を付けて取り消せる")
        void revokeByHr() {
            PaidLeaveRequest approved = submitted().approve(MANAGER, GRANT, REQUESTED_AT);
            PaidLeaveRequest revoked = approved.revoke(HR, "予定を変更して出勤したため",
                    LEAVE_DATE, LEAVE_DATE.atTime(18, 0));

            assertThat(revoked.status()).isEqualTo(LeaveRequestStatus.CANCELED);
            assertThat(revoked.canceledBy()).contains(HR);
            assertThat(revoked.grantId()).isEmpty();
        }

        /** 前日までは本人が取り下げられるので、人事が先回りする理由が無い。 */
        @Test
        @DisplayName("UT-LV-56 取得日の前日には人事は取り消せない")
        void revokeTooEarly() {
            PaidLeaveRequest approved = submitted().approve(MANAGER, GRANT, REQUESTED_AT);

            assertThatThrownBy(() -> approved.revoke(HR, "理由", LEAVE_DATE.minusDays(1),
                    REQUESTED_AT))
                    .isInstanceOf(NotCancelableException.class);
        }

        @Test
        @DisplayName("UT-LV-57 人事の取消に理由が無ければ例外")
        void revokeNeedsReason() {
            PaidLeaveRequest approved = submitted().approve(MANAGER, GRANT, REQUESTED_AT);

            assertThatThrownBy(() -> approved.revoke(HR, "  ", LEAVE_DATE,
                    LEAVE_DATE.atTime(18, 0)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("理由");
        }

        @Test
        @DisplayName("承認されていない申請は人事も取り消せない（取下げの領分）")
        void revokeOnlyApproved() {
            assertThatThrownBy(() -> submitted().revoke(HR, "理由", LEAVE_DATE,
                    LEAVE_DATE.atTime(18, 0)))
                    .isInstanceOf(NotCancelableException.class);
        }
    }

    private static PaidLeaveRequest submitted() {
        return PaidLeaveRequest.submit(PaidLeaveRequestId.generate(), YAMADA, YAMADA,
                LEAVE_DATE, Optional.empty(), REQUESTED_AT);
    }
}
