package jp.co.sample.kintai.leave.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.shared.domain.EmployeeId;

/** 付与そのもの（BR-14）。 */
@DisplayName("年次有給休暇の付与")
class PaidLeaveGrantTest {

    private static final EmployeeId EMPLOYEE = new EmployeeId(UUID.randomUUID());
    private static final LocalDate GRANTED_ON = LocalDate.of(2026, 10, 1);
    private static final LocalDateTime ASSESSED_AT = LocalDateTime.of(2026, 10, 1, 0, 0);

    /**
     * <strong>不付与の年も行として残す。</strong>
     * 残さないと「まだ付与処理をしていない」と「法どおり不付与にした」を区別できない。
     */
    @Test
    @DisplayName("UT-LV-18 不付与の付与は日数 0 で読み出せる")
    void withheldGrantIsKept() {
        PaidLeaveGrant grant = withheld();

        assertThat(grant.isGranted()).isFalse();
        assertThat(grant.days()).isZero();
        assertThat(grant.isValidOn(GRANTED_ON)).isFalse();
    }

    @Test
    @DisplayName("UT-LV-17 法定の範囲外の付与日数は生成できない")
    void invalidDays() {
        assertThatThrownBy(() -> new GrantDecision.Granted(9))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GrantDecision.Granted(21))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * <strong>「不付与 → 付与」の一方向だけを認める。</strong>
     * 一度発生した年休の権利を実績の訂正で消すのは労働者に不利である。
     */
    @Test
    @DisplayName("UT-LV-68 不付与を再判定して 8 割を満たせば付与に変わる")
    void reassessToGranted() {
        PaidLeaveGrant reassessed = withheld().reassess(
                new AttendanceRate(245, 150, 46, "産前産後休業"),
                LocalDateTime.of(2026, 11, 1, 0, 0));

        assertThat(reassessed.isGranted()).isTrue();
        // 6 回目以降は 20 日で頭打ちだが、ここは 1 回目なので 11 日
        assertThat(reassessed.days()).isEqualTo(11);
        assertThat(reassessed.rate().deemedReason()).isEqualTo("産前産後休業");
    }

    @Test
    @DisplayName("再判定しても 8 割に満たなければ不付与のまま")
    void reassessStaysWithheld() {
        PaidLeaveGrant reassessed = withheld().reassess(AttendanceRate.of(245, 150),
                LocalDateTime.of(2026, 11, 1, 0, 0));

        assertThat(reassessed.isGranted()).isFalse();
    }

    @Test
    @DisplayName("UT-LV-69 付与済みは再判定できない")
    void reassessGranted() {
        PaidLeaveGrant granted = new PaidLeaveGrant(PaidLeaveGrantId.generate(), EMPLOYEE, 1,
                GRANTED_ON, AttendanceRate.of(245, 245), new GrantDecision.Granted(11),
                ASSESSED_AT, 1L);

        assertThatThrownBy(() -> granted.reassess(AttendanceRate.of(245, 100), ASSESSED_AT))
                .isInstanceOf(AlreadyGrantedException.class);
    }

    private static PaidLeaveGrant withheld() {
        return new PaidLeaveGrant(PaidLeaveGrantId.generate(), EMPLOYEE, 1, GRANTED_ON,
                AttendanceRate.of(245, 150), new GrantDecision.Withheld(), ASSESSED_AT, 1L);
    }
}
