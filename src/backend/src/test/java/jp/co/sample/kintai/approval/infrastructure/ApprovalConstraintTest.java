package jp.co.sample.kintai.approval.infrastructure;

import static jp.co.sample.kintai.support.ConstraintAssertions.accepted;
import static jp.co.sample.kintai.support.ConstraintAssertions.rejectedBy;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.support.Fixtures;
import jp.co.sample.kintai.support.IntegrationTestBase;

/**
 * 申請・承認・締めの制約（IT-APV-01〜26）。
 *
 * <p>対応する設計は {@code doc/02_詳細設計/05_申請承認と締め/DB設計書.md} の 6 章。
 */
@DisplayName("申請・承認・締めの制約")
class ApprovalConstraintTest extends IntegrationTestBase {

    private static final LocalDate APRIL = LocalDate.of(2026, 4, 1);

    private Fixtures fixtures;
    private UUID subordinate;
    private UUID approver;

    @BeforeEach
    void setUp() {
        fixtures = new Fixtures(jdbc);
        subordinate = fixtures.employee("E0011", LocalDate.of(2026, 1, 1));
        approver = fixtures.employee("E0012", LocalDate.of(2026, 1, 1));
    }

    private Monthly monthly() {
        return new Monthly();
    }

    /** 既定は提出 → 承認 → 締めまで完了した正常な行。 */
    private final class Monthly {
        String status = "CLOSED";
        String submittedAt = "2026-05-01 10:00:00+09";
        UUID submittedBy = subordinate;
        UUID approvedBy = approver;
        String approvedAt = "2026-05-02 10:00:00+09";
        UUID closedBy = approver;
        String closedAt = "2026-05-03 10:00:00+09";

        Monthly status(String v) { status = v; return this; }
        Monthly submittedBy(UUID v) { submittedBy = v; return this; }
        Monthly approvedBy(UUID v) { approvedBy = v; return this; }
        Monthly approvedAt(String v) { approvedAt = v; return this; }

        /** 下書き。決裁の列はすべて空になる。 */
        Monthly draft() {
            status = "DRAFT";
            submittedAt = null; submittedBy = null;
            approvedBy = null; approvedAt = null;
            closedBy = null; closedAt = null;
            return this;
        }

        /** 提出済。承認と締めの列は空になる。 */
        Monthly submitted() {
            status = "SUBMITTED";
            approvedBy = null; approvedAt = null;
            closedBy = null; closedAt = null;
            return this;
        }

        /** 承認済。締めの列だけが空になる。 */
        Monthly approved() {
            status = "APPROVED";
            closedBy = null; closedAt = null;
            return this;
        }

        UUID insert() {
            UUID id = Fixtures.id();
            jdbc.update("""
                    INSERT INTO monthly_attendances (id, employee_id, target_month, status,
                            submitted_at, submitted_by, approved_by, approved_at,
                            closed_by, closed_at)
                    VALUES (?, ?, ?, ?, ?::timestamptz, ?, ?, ?::timestamptz, ?,
                            ?::timestamptz)
                    """, id, subordinate, APRIL, status, submittedAt, submittedBy,
                    approvedBy, approvedAt, closedBy, closedAt);
            return id;
        }
    }

    @Nested
    @DisplayName("月次勤怠の状態")
    class MonthlyState {

        @Test
        @DisplayName("IT-APV-25 提出 → 承認 → 締めまで完了した行を登録できる")
        void closedRowIsAccepted() {
            accepted(() -> monthly().insert());
        }

        @Test
        @DisplayName("IT-APV-01 DRAFT なのに承認者を設定すると拒否される")
        void draftCannotHaveApprover() {
            rejectedBy("monthly_attendances_state_check",
                    () -> monthly().draft().approvedBy(approver).insert());
        }

        @Test
        @DisplayName("IT-APV-02 APPROVED なのに承認者が空だと拒否される")
        void approvedMustHaveApprover() {
            rejectedBy("monthly_attendances_state_check",
                    () -> monthly().approved().approvedBy(null).insert());
        }

        @Test
        @DisplayName("IT-APV-03 CLOSED なのに締めた人が空だと拒否される")
        void closedMustHaveCloser() {
            rejectedBy("monthly_attendances_state_check", () -> {
                Monthly m = monthly();
                m.closedBy = null;
                m.insert();
            });
        }

        @Test
        @DisplayName("IT-APV-04 SUBMITTED なのに提出者が空だと拒否される")
        void submittedMustHaveSubmitter() {
            rejectedBy("monthly_attendances_state_check",
                    () -> monthly().submitted().submittedBy(null).insert());
        }

        @Test
        @DisplayName("IT-APV-05 本人が自分を承認すると拒否される（BR-11 の 4）")
        void selfApprovalIsRejected() {
            rejectedBy("monthly_attendances_no_self_approval_check",
                    () -> monthly().approved().approvedBy(subordinate).insert());
        }

        @Test
        @DisplayName("IT-APV-06 承認日時が提出日時より前だと拒否される")
        void approvalMustFollowSubmission() {
            rejectedBy("monthly_attendances_approved_after_submitted_check",
                    () -> monthly().approved().approvedAt("2026-04-30 10:00:00+09").insert());
        }

        @Test
        @DisplayName("IT-APV-26 updated_at が UPDATE で更新される")
        void updatedAtIsMaintained() {
            UUID id = monthly().insert();
            jdbc.update("UPDATE monthly_attendances SET version = version + 1 WHERE id = ?", id);
            assertThat(jdbc.queryForObject(
                    "SELECT updated_at > created_at FROM monthly_attendances WHERE id = ?",
                    Boolean.class, id)).isTrue();
        }
    }

    @Nested
    @DisplayName("状態遷移の証跡")
    class ApprovalEvents {

        private UUID monthlyId;

        @BeforeEach
        void setUpMonthly() {
            monthlyId = monthly().insert();
        }

        private void event(String kind, String from, String to, String comment) {
            jdbc.update("""
                    INSERT INTO approval_events (id, monthly_attendance_id, event_kind,
                            from_status, to_status, actor_id, comment, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, TIMESTAMPTZ '2026-05-02 10:00:00+09')
                    """, Fixtures.id(), monthlyId, kind, from, to, approver, comment);
        }

        @Test
        @DisplayName("IT-APV-07 締め済みからの遷移は記録できない")
        void closedIsTerminal() {
            rejectedBy("approval_events_transition_check",
                    () -> event("REVOKE_APPROVAL", "CLOSED", "DRAFT", "戻したい"));
        }

        @Test
        @DisplayName("IT-APV-08 DRAFT から CLOSED へ飛ばす遷移は記録できない")
        void cannotSkipToClose() {
            rejectedBy("approval_events_transition_check",
                    () -> event("CLOSE", "DRAFT", "CLOSED", null));
        }

        @Test
        @DisplayName("IT-APV-09 DRAFT から APPROVED へ飛ばす遷移は記録できない")
        void cannotSkipToApprove() {
            rejectedBy("approval_events_transition_check",
                    () -> event("APPROVE", "DRAFT", "APPROVED", null));
        }

        @Test
        @DisplayName("IT-APV-10 差戻しの理由が NULL だと拒否される")
        void rejectionNeedsAReason() {
            rejectedBy("approval_events_reason_required_check",
                    () -> event("REJECT", "SUBMITTED", "DRAFT", null));
        }

        @Test
        @DisplayName("IT-APV-11 差戻しの理由が空文字だと拒否される")
        void rejectionReasonCannotBeEmpty() {
            rejectedBy("approval_events_reason_required_check",
                    () -> event("REJECT", "SUBMITTED", "DRAFT", ""));
        }

        @Test
        @DisplayName("IT-APV-12 差戻しの理由が空白だけだと拒否される")
        void rejectionReasonCannotBeBlank() {
            rejectedBy("approval_events_reason_required_check",
                    () -> event("REJECT", "SUBMITTED", "DRAFT", "   "));
        }

        @Test
        @DisplayName("IT-APV-13 代理提出の理由が空だと拒否される")
        void proxySubmitNeedsAReason() {
            rejectedBy("approval_events_proxy_reason_check",
                    () -> event("PROXY_SUBMIT", "DRAFT", "SUBMITTED", null));
        }

        @Test
        @DisplayName("IT-APV-14 未定義の遷移の種類は拒否される")
        void unknownEventKind() {
            rejectedBy("approval_events_kind_check",
                    () -> event("UNDO", "SUBMITTED", "APPROVED", null));
        }

        /**
         * 同じ {@code SUBMITTED → DRAFT} でも、差戻しと訂正承認による自動差戻しは
         * <strong>意味が違う。</strong> {@code event_kind} で区別できないと、
         * 「何度も差し戻されている社員」という誤読が生まれる。
         */
        @Test
        @DisplayName("IT-APV-15 訂正承認による自動差戻しを、差戻しと区別して記録できる")
        void revertByCorrectionIsDistinctFromRejection() {
            accepted(() -> event("REVERT_BY_CORRECTION", "SUBMITTED", "DRAFT",
                    "打刻訂正の承認による自動差戻し（申請 ID: X）"));

            assertThat(jdbc.queryForObject(
                    "SELECT event_kind FROM approval_events WHERE monthly_attendance_id = ?",
                    String.class, monthlyId)).isEqualTo("REVERT_BY_CORRECTION");
        }

        @Test
        @DisplayName("本人の提出も記録できる")
        void submitIsRecorded() {
            accepted(() -> event("SUBMIT", "DRAFT", "SUBMITTED", null));
        }
    }

    @Nested
    @DisplayName("打刻の訂正申請")
    class CorrectionRequests {

        private UUID requestId;

        @BeforeEach
        void setUpRequest() {
            requestId = insertRequest("退勤打刻の忘れ");
        }

        private UUID insertRequest(String reason) {
            UUID id = Fixtures.id();
            jdbc.update("""
                    INSERT INTO time_clock_correction_requests (id, employee_id, work_date,
                            status, reason, requested_at)
                    VALUES (?, ?, DATE '2026-04-07', 'SUBMITTED', ?,
                            TIMESTAMPTZ '2026-04-08 09:00:00+09')
                    """, id, subordinate, reason);
            return id;
        }

        private void decide(UUID id, String status, UUID decidedBy, String comment) {
            jdbc.update("""
                    UPDATE time_clock_correction_requests
                       SET status = ?, decided_by = ?,
                           decided_at = TIMESTAMPTZ '2026-04-09 10:00:00+09',
                           decision_comment = ?
                     WHERE id = ?
                    """, status, decidedBy, comment, id);
        }

        @Test
        @DisplayName("IT-APV-16 同一勤務日に未処理の申請を 2 件は作れない")
        void onlyOnePendingRequestPerWorkDate() {
            rejectedBy("correction_requests_pending_uk", () -> insertRequest("もう一度"));
        }

        @Test
        @DisplayName("IT-APV-18 却下なのにコメントが空白だけだと拒否される")
        void rejectionNeedsAComment() {
            rejectedBy("correction_requests_rejection_comment_check",
                    () -> decide(requestId, "REJECTED", approver, "  "));
        }

        @Test
        @DisplayName("IT-APV-19 自分の訂正を自分で承認すると拒否される")
        void cannotApproveOwnCorrection() {
            rejectedBy("correction_requests_no_self_approval_check",
                    () -> decide(requestId, "APPROVED", subordinate, null));
        }

        @Test
        @DisplayName("IT-APV-20 本人は自分の申請を取り下げられる")
        void ownerCanCancel() {
            accepted(() -> decide(requestId, "CANCELED", subordinate, null));
        }

        @Test
        @DisplayName("IT-APV-21 他人が取下げとして記録すると拒否される")
        void othersCannotCancel() {
            rejectedBy("correction_requests_cancel_by_self_check",
                    () -> decide(requestId, "CANCELED", approver, null));
        }

        /**
         * 取下げが無いと、誤って申請した本人は
         * <strong>承認者が却下するまで正しい申請を出し直せない。</strong>
         */
        @Test
        @DisplayName("IT-APV-17 取り下げた後は、同じ勤務日に再申請できる")
        void canResubmitAfterCancel() {
            decide(requestId, "CANCELED", subordinate, null);
            accepted(() -> insertRequest("正しい内容で再申請"));
        }
    }

    @Nested
    @DisplayName("訂正の内容")
    class CorrectionItems {

        private UUID requestId;
        private UUID punchId;

        @BeforeEach
        void setUpItems() {
            requestId = Fixtures.id();
            jdbc.update("""
                    INSERT INTO time_clock_correction_requests (id, employee_id, work_date,
                            status, reason, requested_at)
                    VALUES (?, ?, DATE '2026-04-07', 'SUBMITTED', '退勤打刻の忘れ',
                            TIMESTAMPTZ '2026-04-08 09:00:00+09')
                    """, requestId, subordinate);
            punchId = fixtures.punch(subordinate, LocalDate.of(2026, 4, 7),
                    "CLOCK_IN", "2026-04-07 09:00:00+09");
        }

        private void item(String action, UUID targetId, String eventType, String occurredAt) {
            jdbc.update("""
                    INSERT INTO time_clock_correction_items (id, request_id, sequence_no,
                            action, work_date, target_event_id, event_type, occurred_at)
                    VALUES (?, ?, 1, ?, DATE '2026-04-07', ?, ?, ?::timestamptz)
                    """, Fixtures.id(), requestId, action, targetId, eventType, occurredAt);
        }

        @Test
        @DisplayName("IT-APV-22 REVOKE なのに打刻種別を設定すると拒否される")
        void revokeMustNotCarryEventType() {
            rejectedBy("correction_items_variant_check",
                    () -> item("REVOKE", punchId, "CLOCK_IN", null));
        }

        @Test
        @DisplayName("IT-APV-23 ADD なのに取消対象を設定すると拒否される")
        void addMustNotCarryTarget() {
            rejectedBy("correction_items_variant_check",
                    () -> item("ADD", punchId, "CLOCK_OUT", "2026-04-07 18:00:00+09"));
        }

        @Test
        @DisplayName("IT-APV-24 存在しない打刻を取消対象にすると拒否される")
        void targetMustExist() {
            rejectedBy("correction_items_target_fk",
                    () -> item("REVOKE", UUID.fromString("ffffffff-0000-4000-8000-000000000000"),
                            null, null));
        }

        @Test
        @DisplayName("実在する打刻の取消は登録できる")
        void revokeOfAnExistingPunch() {
            accepted(() -> item("REVOKE", punchId, null, null));
        }

        @Test
        @DisplayName("打刻漏れの補完は ADD だけで表せる（取り消す対象が無くてよい）")
        void addOnlyForMissingPunch() {
            accepted(() -> item("ADD", null, "CLOCK_OUT", "2026-04-07 18:00:00+09"));
        }
    }
}
