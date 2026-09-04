package jp.co.sample.kintai.approval.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.attendance.domain.RecordedTimeClockEvent;
import jp.co.sample.kintai.attendance.domain.TimeClockEvent;
import jp.co.sample.kintai.attendance.domain.TimeClockEventId;
import jp.co.sample.kintai.attendance.domain.TimeClockSequenceException;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * 打刻の訂正申請（UT-BR09-01〜12）。
 *
 * <p>アプリケーション層を通さずに検査する。
 * 承認者の判定で先に弾かれる経路があるため、
 * API から試すだけでは<strong>この型が持つ不変条件が働かない</strong>ことがある。
 */
@DisplayName("打刻の訂正申請（BR-09）")
class CorrectionRequestTest {

    private static final LocalDate WORK_DATE = LocalDate.of(2026, 4, 6);
    private static final EmployeeId TARO = new EmployeeId(UUID.randomUUID());
    private static final EmployeeId MANAGER = new EmployeeId(UUID.randomUUID());
    private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 4, 7, 9, 0);
    private static final LocalDateTime DECIDED_AT = LocalDateTime.of(2026, 4, 7, 18, 0);
    private static final String REASON = "退勤打刻を押し忘れました";

    private static final TimeClockEventId CLOCK_IN_ID =
            new TimeClockEventId(UUID.randomUUID());
    private static final TimeClockEventId CLOCK_OUT_ID =
            new TimeClockEventId(UUID.randomUUID());

    private static CorrectionRequest submitted(CorrectionItem... items) {
        return CorrectionRequest.submit(new CorrectionRequestId(UUID.randomUUID()),
                TARO, WORK_DATE, List.of(items), REASON, REQUESTED_AT);
    }

    private static CorrectionRequest addingClockOut() {
        return submitted(new CorrectionItem.Add(
                new TimeClockEvent.ClockOut(WORK_DATE.atTime(19, 0))));
    }

    /** その日の現在の打刻。出勤 9:00 と退勤 17:00。 */
    private static List<RecordedTimeClockEvent> current() {
        return List.of(
                new RecordedTimeClockEvent(CLOCK_IN_ID,
                        new TimeClockEvent.ClockIn(WORK_DATE.atTime(9, 0))),
                new RecordedTimeClockEvent(CLOCK_OUT_ID,
                        new TimeClockEvent.ClockOut(WORK_DATE.atTime(17, 0))));
    }

    @Nested
    @DisplayName("申請の不変条件")
    class Invariants {

        @Test
        @DisplayName("UT-BR09-01 理由が空白だけの申請は作れない")
        void reasonIsRequired() {
            assertThatThrownBy(() -> CorrectionRequest.submit(
                    new CorrectionRequestId(UUID.randomUUID()), TARO, WORK_DATE,
                    List.of(new CorrectionItem.Revoke(CLOCK_OUT_ID)), "  ", REQUESTED_AT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("訂正理由は必須");
        }

        @Test
        @DisplayName("UT-BR09-10 内容が空の申請は作れない")
        void itemsAreRequired() {
            assertThatThrownBy(() -> CorrectionRequest.submit(
                    new CorrectionRequestId(UUID.randomUUID()), TARO, WORK_DATE,
                    List.of(), REASON, REQUESTED_AT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("訂正内容が空");
        }

        /** <strong>決裁は申請より後。</strong> 永続化アダプタが行から直接組み立てる経路がある。 */
        @Test
        @DisplayName("UT-BR09-11 決裁が申請より前の申請は作れない")
        void decisionAfterRequest() {
            assertThatThrownBy(() -> new CorrectionRequest(
                    new CorrectionRequestId(UUID.randomUUID()), TARO, WORK_DATE,
                    List.of(new CorrectionItem.Revoke(CLOCK_OUT_ID)), REASON,
                    CorrectionStatus.APPROVED, DECIDED_AT,
                    Optional.of(MANAGER), Optional.of(REQUESTED_AT), Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("決裁は申請より後");
        }

        @Test
        @DisplayName("UT-BR09-12 申請済なのに決裁者を持つ申請は作れない")
        void pendingHasNoDecision() {
            assertThatThrownBy(() -> new CorrectionRequest(
                    new CorrectionRequestId(UUID.randomUUID()), TARO, WORK_DATE,
                    List.of(new CorrectionItem.Revoke(CLOCK_OUT_ID)), REASON,
                    CorrectionStatus.SUBMITTED, REQUESTED_AT,
                    Optional.of(MANAGER), Optional.of(DECIDED_AT), Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("決裁")
    class Decisions {

        @Test
        @DisplayName("UT-BR09-03 承認すると APPROVED になり、決裁の記録が残る")
        void approve() {
            var approved = addingClockOut().approve(MANAGER, DECIDED_AT);

            assertThat(approved.status()).isEqualTo(CorrectionStatus.APPROVED);
            assertThat(approved.decidedBy()).contains(MANAGER);
            assertThat(approved.decidedAt()).contains(DECIDED_AT);
        }

        /** <strong>自分の訂正は自分で決裁できない。</strong> */
        @Test
        @DisplayName("UT-BR09-13 本人は自分の訂正を承認できない")
        void selfApproval() {
            assertThatThrownBy(() -> addingClockOut().approve(TARO, DECIDED_AT))
                    .isInstanceOf(CorrectionRequest.SelfDecisionException.class);
        }

        @Test
        @DisplayName("UT-BR09-14 本人は自分の訂正を却下もできない")
        void selfRejection() {
            assertThatThrownBy(() -> addingClockOut().reject(TARO, DECIDED_AT, "だめ"))
                    .isInstanceOf(CorrectionRequest.SelfDecisionException.class);
        }

        @Test
        @DisplayName("UT-BR09-15 却下は理由が必須")
        void rejectionRequiresComment() {
            assertThatThrownBy(() -> addingClockOut().reject(MANAGER, DECIDED_AT, "  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("却下には理由が必要");
        }

        /**
         * <strong>取下げは本人だけ。</strong>
         * 取下げは本人の意思表示であり、他人が代わりに行うものではない。
         */
        @Test
        @DisplayName("UT-BR09-06 本人は自分の申請を取り下げられる")
        void cancelBySelf() {
            var canceled = addingClockOut().cancel(TARO, DECIDED_AT);

            assertThat(canceled.status()).isEqualTo(CorrectionStatus.CANCELED);
            assertThat(canceled.decidedBy()).contains(TARO);
        }

        @Test
        @DisplayName("UT-BR09-16 他人は申請を取り下げられない")
        void cancelByOther() {
            assertThatThrownBy(() -> addingClockOut().cancel(MANAGER, DECIDED_AT))
                    .isInstanceOf(CorrectionRequest.NotTheRequesterException.class);
        }

        /** <strong>決着済みの申請には何もできない。</strong> */
        @Test
        @DisplayName("UT-BR09-17 決着済みの申請は再び決裁できない")
        void alreadyDecided() {
            var approved = addingClockOut().approve(MANAGER, DECIDED_AT);

            assertThatThrownBy(() -> approved.approve(MANAGER, DECIDED_AT))
                    .isInstanceOf(CorrectionRequest.AlreadyDecidedException.class);
            assertThatThrownBy(() -> approved.reject(MANAGER, DECIDED_AT, "だめ"))
                    .isInstanceOf(CorrectionRequest.AlreadyDecidedException.class);
            assertThatThrownBy(() -> approved.cancel(TARO, DECIDED_AT))
                    .isInstanceOf(CorrectionRequest.AlreadyDecidedException.class);
        }
    }

    @Nested
    @DisplayName("訂正を適用した結果")
    class Applying {

        /**
         * <strong>取消と追加の組み合わせで「変更」を表す。</strong>
         * 退勤 17:00 を取り消して 19:00 を足すと、退勤だけが差し替わる。
         */
        @Test
        @DisplayName("UT-BR09-18 取消と追加で打刻を差し替えられる")
        void replace() {
            var request = submitted(new CorrectionItem.Revoke(CLOCK_OUT_ID),
                    new CorrectionItem.Add(
                            new TimeClockEvent.ClockOut(WORK_DATE.atTime(19, 0))));

            var applied = request.applyTo(current());

            assertThat(applied.events()).extracting(TimeClockEvent::occurredAt)
                    .containsExactly(WORK_DATE.atTime(9, 0), WORK_DATE.atTime(19, 0));
        }

        /** <strong>打刻漏れの補完は追加だけでよい。</strong> 取り消す対象が無い。 */
        @Test
        @DisplayName("UT-BR09-08 追加だけの申請も適用できる")
        void addOnly() {
            var request = submitted(new CorrectionItem.Add(
                    new TimeClockEvent.BreakStart(WORK_DATE.atTime(12, 0))),
                    new CorrectionItem.Add(
                            new TimeClockEvent.BreakEnd(WORK_DATE.atTime(13, 0))));

            assertThat(request.applyTo(current()).events()).hasSize(4);
        }

        /**
         * <strong>適用すると壊れる訂正は、申請の時点で分かる。</strong>
         * 出勤を取り消すと、退勤だけが残って状態機械が成り立たない。
         */
        @Test
        @DisplayName("UT-BR09-02 適用すると打刻列が壊れる訂正は検出できる")
        void brokenSequence() {
            var request = submitted(new CorrectionItem.Revoke(CLOCK_IN_ID));

            assertThatThrownBy(() -> request.applyTo(current()).validateTransitions())
                    .isInstanceOf(TimeClockSequenceException.class);
        }

        /**
         * <strong>実在しない打刻を指す申請を見つける。</strong>
         * DB の外部キーでも弾かれるが、外部キー違反は利用者に説明できない。
         */
        @Test
        @DisplayName("UT-BR09-19 実在しない取消対象を検出できる")
        void missingTarget() {
            var stranger = new TimeClockEventId(UUID.randomUUID());
            var request = submitted(new CorrectionItem.Revoke(stranger));

            assertThat(request.missingTargets(current())).containsExactly(stranger);
            assertThat(addingClockOut().missingTargets(current())).isEmpty();
        }
    }
}
