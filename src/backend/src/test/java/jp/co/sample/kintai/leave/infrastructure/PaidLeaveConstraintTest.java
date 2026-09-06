package jp.co.sample.kintai.leave.infrastructure;

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
 * 年次有給休暇の制約（IT-LV-01〜30 / 68〜77）。
 *
 * <p>対応する設計は {@code doc/02_詳細設計/06_年次有給休暇/DB設計書.md} の 7 章。
 *
 * <p><strong>「拒否された」ではなく「狙った制約で拒否された」ことを確かめる</strong>
 * （CLAUDE.md 落とし穴 17・25）。
 */
@DisplayName("年次有給休暇の制約")
class PaidLeaveConstraintTest extends IntegrationTestBase {

    private Fixtures fixtures;
    private UUID yamada;
    private UUID manager;

    @BeforeEach
    void setUp() {
        fixtures = new Fixtures(jdbc);
        yamada = fixtures.employee("E0021", LocalDate.of(2024, 4, 1));
        manager = fixtures.employee("E0022", LocalDate.of(2020, 4, 1));
    }

    // --- 付与 ---------------------------------------------------------------

    @Nested
    @DisplayName("付与")
    class Grants {

        @Test
        @DisplayName("IT-LV-01 不付与なのに日数があると拒否される")
        void withheldWithDays() {
            rejectedBy("paid_leave_grants_decision_check",
                    () -> grant().index(90).on("2090-01-01").granted(false).days(10).insert());
        }

        @Test
        @DisplayName("IT-LV-02 付与なのに日数が NULL だと拒否される")
        void grantedWithoutDays() {
            rejectedBy("paid_leave_grants_decision_check",
                    () -> grant().index(91).on("2091-01-01").granted(true).days(null).insert());
        }

        @Test
        @DisplayName("IT-LV-03 付与日数が 9 日だと拒否される")
        void belowMinimum() {
            rejectedBy("paid_leave_grants_days_check",
                    () -> grant().index(92).on("2092-01-01").days(9).insert());
        }

        /** 下限だけでなく<strong>上限</strong>も置く（落とし穴 15）。 */
        @Test
        @DisplayName("IT-LV-04 付与日数が 21 日だと拒否される")
        void aboveMaximum() {
            rejectedBy("paid_leave_grants_days_check",
                    () -> grant().index(93).on("2093-01-01").days(21).insert());
        }

        @Test
        @DisplayName("IT-LV-05 出勤日が全労働日を超えると拒否される")
        void attendedExceedsTotal() {
            rejectedBy("paid_leave_grants_rate_check",
                    () -> grant().index(94).on("2094-01-01").total(120).attended(121).insert());
        }

        /** 連番だけが衝突する（付与日は別）。付与処理の冪等性の根拠。 */
        @Test
        @DisplayName("IT-LV-06 同じ社員に同じ連番を 2 回入れると拒否される")
        void duplicateIndex() {
            grant().index(0).on("2024-10-01").insert();

            rejectedBy("paid_leave_grants_employee_index_uk",
                    () -> grant().index(0).on("2095-01-01").insert());
        }

        /** 付与日だけが衝突する（連番は別）。 */
        @Test
        @DisplayName("IT-LV-07 同じ社員に同じ付与日を 2 回入れると拒否される")
        void duplicateDate() {
            grant().index(0).on("2024-10-01").insert();

            rejectedBy("paid_leave_grants_employee_date_uk",
                    () -> grant().index(96).on("2024-10-01").insert());
        }

        /** 正常系は条件ごとに 1 件ずつ立てる（落とし穴 24）。 */
        @Test
        @DisplayName("IT-LV-08 正常な付与と不付与の 2 行を作れる")
        void normal() {
            accepted(() -> grant().index(0).on("2024-10-01").granted(true).days(10).insert());
            accepted(() -> grant().index(1).on("2025-10-01").granted(false).days(null)
                    .total(245).attended(180).insert());
        }

        /**
         * <strong>和で検査する。</strong>
         * 各項目が分母以下であることだけを見る制約では、この行が通ってしまう。
         */
        @Test
        @DisplayName("IT-LV-68 出勤日と出勤扱いの和が全労働日を超えると拒否される")
        void deemedSumExceeds() {
            rejectedBy("paid_leave_grants_rate_check",
                    () -> grant().index(70).on("2070-01-01").total(120).attended(100)
                            .deemed(21, "産前産後休業").insert());
        }

        @Test
        @DisplayName("IT-LV-69 出勤扱いを申告して理由が空白のみだと拒否される")
        void deemedWithoutReason() {
            rejectedBy("paid_leave_grants_deemed_reason_check",
                    () -> grant().index(71).on("2071-01-01").total(120).attended(100)
                            .deemed(20, "   ").insert());
        }

        /** 申告していないなら理由は要らない。閾値の反対側。 */
        @Test
        @DisplayName("IT-LV-70 出勤扱いが 0 なら理由が無くても通る")
        void noDeemedNoReason() {
            accepted(() -> grant().index(72).on("2072-01-01").total(120).attended(118)
                    .deemed(0, null).insert());
        }
    }

    // --- 申請 ---------------------------------------------------------------

    @Nested
    @DisplayName("申請")
    class Requests {

        private UUID grantId;

        @BeforeEach
        void grantTen() {
            grantId = grant().index(0).on("2024-10-01").days(10).insert();
        }

        @Test
        @DisplayName("IT-LV-09 申請中なのに配分先があると拒否される")
        void submittedWithGrant() {
            rejectedBy("paid_leave_requests_state_check",
                    () -> request().on("2090-01-05").status("SUBMITTED").grant(grantId).insert());
        }

        /**
         * 自己承認の禁止にも部分一意インデックスにも掛からない値を使う。
         * 別の制約に先に引っかかると、狙った検証は行われていない。
         */
        @Test
        @DisplayName("IT-LV-10 承認済みなのに配分先が NULL だと拒否される")
        void approvedWithoutGrant() {
            rejectedBy("paid_leave_requests_state_check",
                    () -> request().on("2090-01-06").status("APPROVED").grant(null)
                            .decidedBy(manager).insert());
        }

        @Test
        @DisplayName("IT-LV-11 却下なのにコメントが空文字だと拒否される")
        void rejectedWithEmptyComment() {
            rejectedBy("paid_leave_requests_state_check",
                    () -> request().on("2090-01-07").status("REJECTED").decidedBy(manager)
                            .comment("").insert());
        }

        @Test
        @DisplayName("IT-LV-12 却下なのにコメントが空白のみだと拒否される")
        void rejectedWithBlankComment() {
            rejectedBy("paid_leave_requests_state_check",
                    () -> request().on("2090-01-08").status("REJECTED").decidedBy(manager)
                            .comment("   ").insert());
        }

        /** 取消は配分を外す。残ったままだと残日数が戻らない。 */
        @Test
        @DisplayName("IT-LV-13 取消なのに配分先が残っていると拒否される")
        void canceledWithGrant() {
            rejectedBy("paid_leave_requests_state_check",
                    () -> request().on("2090-01-09").status("CANCELED").grant(grantId)
                            .canceledBy(yamada).insert());
        }

        @Test
        @DisplayName("IT-LV-14 自分の年休を自分で承認すると拒否される")
        void selfApproval() {
            rejectedBy("paid_leave_requests_no_self_decision_check",
                    () -> request().on("2090-01-10").status("APPROVED").grant(grantId)
                            .decidedBy(yamada).insert());
        }

        @Test
        @DisplayName("IT-LV-15 決裁日時が申請日時より前だと拒否される")
        void decidedBeforeRequested() {
            rejectedBy("paid_leave_requests_decided_after_requested_check",
                    () -> request().on("2090-01-11").status("APPROVED").grant(grantId)
                            .decidedBy(manager).decidedAt("2020-01-01 10:00:00+09").insert());
        }

        @Test
        @DisplayName("IT-LV-16 同じ日に未処理の申請を 2 件入れると拒否される")
        void duplicatePending() {
            request().on("2026-04-06").insert();

            rejectedBy("paid_leave_requests_active_uk",
                    () -> request().on("2026-04-06").insert());
        }

        @Test
        @DisplayName("IT-LV-17 承認済みと同じ日に新しい申請を入れると拒否される")
        void duplicateWithApproved() {
            request().on("2026-04-07").status("APPROVED").grant(grantId)
                    .decidedBy(manager).insert();

            rejectedBy("paid_leave_requests_active_uk",
                    () -> request().on("2026-04-07").insert());
        }

        /** 却下・取下げは部分一意インデックスの対象から外れるので再申請できる。 */
        @Test
        @DisplayName("IT-LV-18 取り下げた日には再申請できる")
        void resubmitAfterCancel() {
            request().on("2026-04-08").status("CANCELED").canceledBy(yamada).insert();

            accepted(() -> request().on("2026-04-08").insert());
        }

        @Test
        @DisplayName("IT-LV-19 却下された日には再申請できる")
        void resubmitAfterReject() {
            request().on("2026-04-09").status("REJECTED").decidedBy(manager)
                    .comment("繁忙のため").insert();

            accepted(() -> request().on("2026-04-09").insert());
        }

        /**
         * <strong>他人の付与から自分の年休を消化する行を作れない。</strong>
         * 単純な {@code grant_id} への外部キーだと作れてしまい、
         * 残日数の集計は付与側を社員で絞るので、
         * その行はどちらの社員の残日数からも消える（落とし穴 42 と同型）。
         */
        @Test
        @DisplayName("IT-LV-71 他人の付与を配分先にすると拒否される")
        void othersGrant() {
            UUID othersGrant = grant().employee(manager).index(0).on("2020-10-01")
                    .days(10).insert();

            rejectedBy("paid_leave_requests_grant_fk",
                    () -> request().on("2091-02-01").status("APPROVED").grant(othersGrant)
                            .decidedBy(manager).insert());
        }

        @Test
        @DisplayName("IT-LV-72 本人以外が取り消して理由が無いと拒否される")
        void revokeWithoutReason() {
            rejectedBy("paid_leave_requests_revoke_reason_check",
                    () -> request().on("2091-02-02").status("CANCELED").canceledBy(manager)
                            .insert());
        }

        @Test
        @DisplayName("IT-LV-73 本人以外が理由を付けて取り消せる（人事・BR-16）")
        void revokeWithReason() {
            accepted(() -> request().on("2091-02-03").status("CANCELED").canceledBy(manager)
                    .comment("出勤したため人事が取り消した").insert());
        }

        @Test
        @DisplayName("IT-LV-74 取消なのに取り消した人が空だと拒否される")
        void canceledWithoutActor() {
            rejectedBy("paid_leave_requests_state_check",
                    () -> request().on("2091-02-04").status("CANCELED").canceledBy(null)
                            .insert());
        }

        /** 取消後も、誰がいつ承認したかを追える。 */
        @Test
        @DisplayName("IT-LV-75 承認済みを取り消しても承認者が残る")
        void keepsApprover() {
            accepted(() -> request().on("2091-02-05").status("CANCELED")
                    .decidedBy(manager).canceledBy(yamada).insert());

            assertThat(jdbc.queryForObject(
                    "SELECT decided_by FROM paid_leave_requests WHERE leave_date = ?",
                    UUID.class, LocalDate.of(2091, 2, 5))).isEqualTo(manager);
        }

        @Test
        @DisplayName("IT-LV-29 申請 → 承認 → 取消の 1 巡が通る")
        void fullCycle() {
            UUID id = request().on("2026-04-10").insert();

            accepted(() -> jdbc.update("""
                    UPDATE paid_leave_requests
                       SET status = 'APPROVED', grant_id = ?, decided_by = ?,
                           decided_at = now(), version = 2
                     WHERE id = ?
                    """, grantId, manager, id));
            accepted(() -> jdbc.update("""
                    UPDATE paid_leave_requests
                       SET status = 'CANCELED', grant_id = NULL, canceled_by = ?,
                           canceled_at = now(), version = 3
                     WHERE id = ?
                    """, yamada, id));
        }

        @Test
        @DisplayName("IT-LV-30 updated_at が UPDATE で更新される")
        void touchesUpdatedAt() {
            UUID id = request().on("2026-04-11").insert();
            jdbc.update("UPDATE paid_leave_requests SET reason = ? WHERE id = ?", "変更", id);

            assertThat(jdbc.queryForObject("""
                    SELECT updated_at > created_at FROM paid_leave_requests WHERE id = ?
                    """, Boolean.class, id)).isTrue();
        }
    }

    // --- 証跡 ---------------------------------------------------------------

    @Nested
    @DisplayName("証跡")
    class Events {

        private UUID requestId;

        @BeforeEach
        void submitOne() {
            grant().index(0).on("2024-10-01").days(10).insert();
            requestId = request().on("2026-04-06").insert();
        }

        @Test
        @DisplayName("IT-LV-20 却下から承認への遷移は記録できない")
        void backwards() {
            rejectedBy("paid_leave_request_events_transition_check",
                    () -> event("REJECTED", "APPROVED", "APPROVE", manager, null));
        }

        @Test
        @DisplayName("IT-LV-21 取消からの遷移は記録できない")
        void fromCanceled() {
            rejectedBy("paid_leave_request_events_transition_check",
                    () -> event("CANCELED", "SUBMITTED", "SUBMIT", yamada, null));
        }

        /** 遷移の組は正しいが種類が食い違う。 */
        @Test
        @DisplayName("IT-LV-22 遷移の組と種類が食い違うと記録できない")
        void mismatchedKind() {
            rejectedBy("paid_leave_request_events_transition_check",
                    () -> event("SUBMITTED", "APPROVED", "CANCEL", manager, null));
        }

        @Test
        @DisplayName("IT-LV-23 未定義の種類は記録できない")
        void unknownKind() {
            rejectedBy("paid_leave_request_events_kind_check",
                    () -> event("SUBMITTED", "APPROVED", "RUBBER_STAMP", manager, null));
        }

        @Test
        @DisplayName("IT-LV-24 却下の理由が無いと記録できない")
        void rejectNeedsReason() {
            rejectedBy("paid_leave_request_events_reason_check",
                    () -> event("SUBMITTED", "REJECTED", "REJECT", manager, null));
        }

        @Test
        @DisplayName("IT-LV-25 承認済みからの取消は記録できる")
        void cancelFromApproved() {
            accepted(() -> event("APPROVED", "CANCELED", "CANCEL", yamada, null));
        }

        /**
         * 本人の取下げ（{@code CANCEL}）と区別する。
         * 一緒にすると「頻繁に年休を取り消す社員」という誤った読み取りが生まれる。
         */
        @Test
        @DisplayName("IT-LV-76 人事による取消を理由つきで記録できる")
        void revoke() {
            accepted(() -> event("APPROVED", "CANCELED", "REVOKE", manager,
                    "本人が出勤したため取り消した"));
        }

        @Test
        @DisplayName("IT-LV-77 人事による取消に理由が無いと記録できない")
        void revokeNeedsReason() {
            rejectedBy("paid_leave_request_events_reason_check",
                    () -> event("APPROVED", "CANCELED", "REVOKE", manager, null));
        }

        private void event(String from, String to, String kind, UUID actor, String comment) {
            jdbc.update("""
                    INSERT INTO paid_leave_request_events (id, paid_leave_request_id,
                            from_status, to_status, event_kind, actor_id, comment, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, now())
                    """, Fixtures.id(), requestId, from, to, kind, actor, comment);
        }
    }

    // --- 他コンテキストの表への追加 ------------------------------------------

    @Nested
    @DisplayName("approval_events と monthly_settlements への追加")
    class OtherTables {

        /**
         * 年休の承認は提出済みの月次勤怠を下書きへ戻す。
         * {@code REVERT_BY_CORRECTION} を流用すると、
         * 打刻を一度も訂正していない社員の履歴に「訂正による差戻し」が並ぶ。
         */
        @Test
        @DisplayName("IT-LV-26 REVERT_BY_LEAVE を理由つきで記録できる")
        void revertByLeave() {
            UUID monthly = monthlyAttendance();

            accepted(() -> approvalEvent(monthly, "REVERT_BY_LEAVE",
                    "年休の承認により内容が変わったため"));
        }

        @Test
        @DisplayName("IT-LV-27 REVERT_BY_LEAVE を理由なしで記録すると拒否される")
        void revertByLeaveWithoutReason() {
            UUID monthly = monthlyAttendance();

            rejectedBy("approval_events_reason_required_check",
                    () -> approvalEvent(monthly, "REVERT_BY_LEAVE", null));
        }

        @Test
        @DisplayName("IT-LV-28 月次清算の年休日数が負だと拒否される")
        void negativePaidLeaveDays() {
            rejectedBy("monthly_settlements_paid_leave_days_check",
                    () -> settlement(-1));
        }

        /** 年休を取らない月は 0 のまま。閾値の反対側。 */
        @Test
        @DisplayName("月次清算の年休日数は 0 なら通る")
        void zeroPaidLeaveDays() {
            accepted(() -> settlement(0));
        }

        private UUID monthlyAttendance() {
            UUID id = Fixtures.id();
            jdbc.update("""
                    INSERT INTO monthly_attendances (id, employee_id, target_month, status,
                            submitted_at, submitted_by, version)
                    VALUES (?, ?, DATE '2026-04-01', 'SUBMITTED', now(), ?, 1)
                    """, id, yamada, yamada);
            return id;
        }

        private void approvalEvent(UUID monthlyId, String kind, String comment) {
            jdbc.update("""
                    INSERT INTO approval_events (id, monthly_attendance_id, from_status,
                            to_status, event_kind, actor_id, comment, occurred_at)
                    VALUES (?, ?, 'SUBMITTED', 'DRAFT', ?, ?, ?, now())
                    """, Fixtures.id(), monthlyId, kind, manager, comment);
        }

        private void settlement(int paidLeaveDays) {
            jdbc.update("""
                    INSERT INTO monthly_settlements (id, employee_id, target_month,
                            period_from, period_to_exclusive, work_rule_series_id,
                            working_time_system, working_minutes, legal_holiday_minutes,
                            target_working_minutes, scheduled_total_minutes,
                            statutory_total_limit_minutes, overtime_minutes, night_minutes,
                            exceeds_monthly_agreement_limit, exceeds_annual_agreement_limit,
                            calculated_at, version, paid_leave_days)
                    VALUES (?, ?, DATE '2026-04-01', DATE '2026-04-01', DATE '2026-05-01',
                            ?, 'FIXED', 9600, 0, 9600, 9600, 10285, 0, 0, false, false,
                            now(), 1, ?)
                    """, Fixtures.id(), yamada, fixtures.workRuleSeries("標準勤務"),
                    paidLeaveDays);
        }
    }

    // --- 行の組み立て --------------------------------------------------------

    private Grant grant() {
        return new Grant();
    }

    private Request request() {
        return new Request();
    }

    /** 既定は「10 日を付与した」正常な行。検証では 1 つだけ変える（落とし穴 12）。 */
    private final class Grant {
        UUID employeeId = yamada;
        int index;
        String grantedOn = "2024-10-01";
        boolean granted = true;
        Integer days = 10;
        int total = 120;
        int attended = 118;
        int deemed;
        String deemedReason;

        Grant employee(UUID v) { employeeId = v; return this; }
        Grant index(int v) { index = v; return this; }
        Grant on(String v) { grantedOn = v; return this; }
        Grant granted(boolean v) { granted = v; return this; }
        Grant days(Integer v) { days = v; return this; }
        Grant total(int v) { total = v; return this; }
        Grant attended(int v) { attended = v; return this; }
        Grant deemed(int v, String reason) { deemed = v; deemedReason = reason; return this; }

        UUID insert() {
            UUID id = Fixtures.id();
            jdbc.update("""
                    INSERT INTO paid_leave_grants (id, employee_id, grant_index, granted_on,
                            granted, days, total_working_days, attended_days,
                            deemed_attended_days, deemed_reason, assessed_at, version)
                    VALUES (?, ?, ?, CAST(? AS date), ?, ?, ?, ?, ?, ?, now(), 1)
                    """, id, employeeId, index, grantedOn, granted, days, total, attended,
                    deemed, deemedReason);
            return id;
        }
    }

    /** 既定は「申請中」の正常な行。 */
    private final class Request {
        String leaveDate = "2026-04-06";
        String status = "SUBMITTED";
        UUID grantId;
        UUID decidedBy;
        String decidedAt = "2026-04-02 10:00:00+09";
        String comment;
        UUID canceledBy;

        Request on(String v) { leaveDate = v; return this; }
        Request status(String v) { status = v; return this; }
        Request grant(UUID v) { grantId = v; return this; }
        Request decidedBy(UUID v) { decidedBy = v; return this; }
        Request decidedAt(String v) { decidedAt = v; return this; }
        Request comment(String v) { comment = v; return this; }
        Request canceledBy(UUID v) { canceledBy = v; return this; }

        UUID insert() {
            UUID id = Fixtures.id();
            boolean decided = decidedBy != null;
            boolean canceled = "CANCELED".equals(status);
            jdbc.update("""
                    INSERT INTO paid_leave_requests (id, employee_id, leave_date, status,
                            requested_at, grant_id, decided_by, decided_at, comment,
                            canceled_by, canceled_at, version)
                    VALUES (?, ?, CAST(? AS date), ?, TIMESTAMPTZ '2026-04-01 09:00:00+09',
                            ?, ?, CAST(? AS timestamptz), ?, ?, CAST(? AS timestamptz), 1)
                    """, id, yamada, leaveDate, status, grantId, decidedBy,
                    decided ? decidedAt : null, comment, canceledBy,
                    canceled || canceledBy != null ? "2026-04-05 10:00:00+09" : null);
            return id;
        }
    }
}
