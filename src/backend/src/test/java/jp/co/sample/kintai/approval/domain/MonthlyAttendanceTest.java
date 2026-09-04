package jp.co.sample.kintai.approval.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * 月次勤怠の状態遷移（BR-10）。
 *
 * <p><strong>アプリケーション層を通さずに検査する。</strong>
 * 承認者の判定（BR-11）で先に弾かれる経路があるため、
 * API から試すだけでは<strong>この型が持つ不変条件が一度も働かない。</strong>
 * 実際、自己承認の禁止を削除しても API のテストは 1 件も落ちなかった。
 */
@DisplayName("月次勤怠の状態遷移（BR-10）")
class MonthlyAttendanceTest {

    private static final YearMonth APRIL = YearMonth.of(2026, 4);
    private static final EmployeeId TARO = new EmployeeId(UUID.randomUUID());
    private static final EmployeeId MANAGER = new EmployeeId(UUID.randomUUID());
    private static final EmployeeId HR = new EmployeeId(UUID.randomUUID());
    private static final LocalDateTime SUBMITTED_AT = LocalDateTime.of(2026, 5, 1, 9, 0);
    private static final LocalDateTime APPROVED_AT = LocalDateTime.of(2026, 5, 2, 9, 0);
    private static final LocalDateTime CLOSED_AT = LocalDateTime.of(2026, 5, 3, 9, 0);

    private static MonthlyAttendance draft() {
        return MonthlyAttendance.draft(new MonthlyAttendanceId(UUID.randomUUID()),
                TARO, APRIL);
    }

    private static MonthlyAttendance submitted() {
        return draft().submit(TARO, SUBMITTED_AT);
    }

    private static MonthlyAttendance approved() {
        return submitted().approve(MANAGER, APPROVED_AT);
    }

    private static MonthlyAttendance closed() {
        return approved().close(HR, CLOSED_AT);
    }

    @Nested
    @DisplayName("正常な遷移")
    class HappyPath {

        @Test
        @DisplayName("UT-BR10-01 下書き → 提出済 → 承認済 → 締め済 と進む")
        void fullPath() {
            assertThat(draft().status().state()).isEqualTo(AttendanceState.DRAFT);
            assertThat(submitted().status().state()).isEqualTo(AttendanceState.SUBMITTED);
            assertThat(approved().status().state()).isEqualTo(AttendanceState.APPROVED);
            assertThat(closed().status().state()).isEqualTo(AttendanceState.CLOSED);
            assertThat(closed().isClosed()).isTrue();
        }

        @Test
        @DisplayName("UT-BR10-05 差戻しで下書きに戻り、打刻を受け付ける")
        void reject() {
            var rejected = submitted().reject();

            assertThat(rejected.status().state()).isEqualTo(AttendanceState.DRAFT);
            assertThat(rejected.status().acceptsTimeClock()).isTrue();
        }

        @Test
        @DisplayName("UT-BR10-10 承認の取消で下書きに戻る")
        void revokeApproval() {
            assertThat(approved().revokeApproval().status().state())
                    .isEqualTo(AttendanceState.DRAFT);
        }

        /** 状態ごとに持つ項目が違う。<strong>承認済には必ず承認者がいる。</strong> */
        @Test
        @DisplayName("UT-BR10-19 承認済は提出と承認の記録を必ず持つ")
        void approvedKeepsTheRecords() {
            var status = (MonthlyAttendanceStatus.Approved) approved().status();

            assertThat(status.submittedBy()).isEqualTo(TARO);
            assertThat(status.submittedAt()).isEqualTo(SUBMITTED_AT);
            assertThat(status.approvedBy()).isEqualTo(MANAGER);
            assertThat(status.approvedAt()).isEqualTo(APPROVED_AT);
        }
    }

    @Nested
    @DisplayName("自己承認の禁止（BR-11 の 4）")
    class SelfApproval {

        /**
         * <strong>比べる相手は提出者ではなく、対象の社員である。</strong>
         * 代理提出では提出者が人事になるため、提出者と比べると本人の承認を見逃す。
         */
        @Test
        @DisplayName("UT-BR10-20 本人は自分の勤怠を承認できない")
        void selfApprovalIsRejected() {
            assertThatThrownBy(() -> submitted().approve(TARO, APPROVED_AT))
                    .isInstanceOf(MonthlyAttendance.SelfApprovalException.class)
                    .hasMessageContaining("自分の勤怠は承認できません");
        }

        /**
         * <strong>代理提出でも見逃さない。</strong>
         * 人事が代理提出した月を本人が承認しようとする経路。
         * 提出者（人事）と承認者（本人）は別人なので、
         * 提出者と比べる実装ではここが通ってしまう。
         */
        @Test
        @DisplayName("UT-BR10-21 代理提出された月でも、本人は承認できない")
        void selfApprovalAfterProxySubmission() {
            var proxySubmitted = draft().submit(HR, SUBMITTED_AT);

            assertThatThrownBy(() -> proxySubmitted.approve(TARO, APPROVED_AT))
                    .isInstanceOf(MonthlyAttendance.SelfApprovalException.class);
        }
    }

    @Nested
    @DisplayName("定義していない遷移")
    class InvalidTransitions {

        /**
         * <strong>締め済からの遷移は「実行時に拒否する」。</strong>
         * 遷移は状態ではなく集約（{@link MonthlyAttendance}）が持つ。
         * 自己承認の判定に対象社員の ID が要るためで、
         * その結果 {@code closed.reject()} は<strong>コンパイルは通る。</strong>
         * 確定値が動かないことは、この検査が守る。
         */
        @Test
        @DisplayName("UT-BR10-08 締め済からは戻せない")
        void closedIsFinal() {
            var closed = closed();

            assertThatThrownBy(closed::revokeApproval)
                    .isInstanceOf(MonthlyAttendance.InvalidTransitionException.class);
            assertThatThrownBy(closed::reject)
                    .isInstanceOf(MonthlyAttendance.InvalidTransitionException.class);
            assertThatThrownBy(closed::revertByCorrection)
                    .isInstanceOf(MonthlyAttendance.InvalidTransitionException.class);
            assertThatThrownBy(() -> closed.submit(TARO, SUBMITTED_AT))
                    .isInstanceOf(MonthlyAttendance.InvalidTransitionException.class);
            assertThatThrownBy(() -> closed.close(HR, CLOSED_AT))
                    .isInstanceOf(MonthlyAttendance.InvalidTransitionException.class);
        }

        @Test
        @DisplayName("UT-BR10-22 下書きは承認できない")
        void draftCannotBeApproved() {
            assertThatThrownBy(() -> draft().approve(MANAGER, APPROVED_AT))
                    .isInstanceOf(MonthlyAttendance.InvalidTransitionException.class);
        }

        @Test
        @DisplayName("UT-BR10-23 提出済は締められない（承認を経る）")
        void submittedCannotBeClosed() {
            assertThatThrownBy(() -> submitted().close(HR, CLOSED_AT))
                    .isInstanceOf(MonthlyAttendance.InvalidTransitionException.class);
        }

        @Test
        @DisplayName("UT-BR10-24 二重提出はできない")
        void doubleSubmission() {
            assertThatThrownBy(() -> submitted().submit(TARO, SUBMITTED_AT))
                    .isInstanceOf(MonthlyAttendance.InvalidTransitionException.class);
        }

        /**
         * <strong>訂正による差戻しも、提出済からしか行えない。</strong>
         * アプリケーション層は提出済でなければ何もしないので、
         * サービス経由のテストではこの検査が一度も働かない。
         */
        @Test
        @DisplayName("UT-BR10-25 訂正による差戻しは提出済からしか行えない")
        void revertByCorrectionRequiresSubmitted() {
            assertThatThrownBy(() -> draft().revertByCorrection())
                    .isInstanceOf(MonthlyAttendance.InvalidTransitionException.class);
            assertThatThrownBy(() -> approved().revertByCorrection())
                    .isInstanceOf(MonthlyAttendance.InvalidTransitionException.class);
        }
    }

    @Nested
    @DisplayName("状態が受け付ける操作")
    class Acceptance {

        /**
         * <strong>「打刻」と「訂正申請」を分ける。</strong>
         * 1 つにまとめると提出済が真を返し、本人が提出後に直接打刻できてしまう。
         * 月次勤怠は提出済のまま内容だけが変わり、
         * 承認者が確認した内容と実際に確定される内容が食い違う。
         */
        @Test
        @DisplayName("UT-BR10-12 提出済の月に本人は直接打刻できない")
        void submittedRejectsTimeClock() {
            assertThat(submitted().status().acceptsTimeClock()).isFalse();
        }

        @Test
        @DisplayName("UT-BR10-13 提出済の月でも訂正申請は受け付ける")
        void submittedAcceptsCorrectionRequest() {
            assertThat(submitted().status().acceptsCorrectionRequest()).isTrue();
        }

        @Test
        @DisplayName("UT-BR10-06 承認済の月には打刻できない")
        void approvedRejectsTimeClock() {
            assertThat(approved().status().acceptsTimeClock()).isFalse();
        }

        @Test
        @DisplayName("UT-BR10-07 締め済の月は打刻も訂正申請も受け付けない")
        void closedAcceptsNothing() {
            var status = closed().status();

            assertThat(status.acceptsTimeClock()).isFalse();
            assertThat(status.acceptsCorrectionRequest()).isFalse();
        }

        @Test
        @DisplayName("UT-BR10-26 下書きは打刻も訂正申請も受け付ける")
        void draftAcceptsBoth() {
            var status = draft().status();

            assertThat(status.acceptsTimeClock()).isTrue();
            assertThat(status.acceptsCorrectionRequest()).isTrue();
        }
    }

    /**
     * 時系列の整合。
     *
     * <p>これらの型は<strong>永続化アダプタが DB の行から直接組み立てる。</strong>
     * 遷移メソッドを経ない生成経路があるので、
     * 壊れた行をそのまま通さないことをここで確かめる。
     */
    @Nested
    @DisplayName("時系列の整合")
    class Chronology {

        @Test
        @DisplayName("UT-BR10-27 承認が提出より前の承認済は作れない")
        void approvalBeforeSubmission() {
            assertThatThrownBy(() -> new MonthlyAttendanceStatus.Approved(
                    TARO, APPROVED_AT, MANAGER, SUBMITTED_AT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("承認は提出より後");
        }

        /** {@code Approved} が守る不変条件は、{@code Closed} でも守られる。 */
        @Test
        @DisplayName("UT-BR10-28 承認が提出より前の締め済は作れない")
        void approvalBeforeSubmissionInClosed() {
            assertThatThrownBy(() -> new MonthlyAttendanceStatus.Closed(
                    TARO, APPROVED_AT, MANAGER, SUBMITTED_AT, HR, CLOSED_AT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("承認は提出より後");
        }

        @Test
        @DisplayName("UT-BR10-29 締めが承認より前の締め済は作れない")
        void closureBeforeApproval() {
            assertThatThrownBy(() -> new MonthlyAttendanceStatus.Closed(
                    TARO, SUBMITTED_AT, MANAGER, CLOSED_AT, HR, APPROVED_AT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("締めは承認より後");
        }
    }
}
