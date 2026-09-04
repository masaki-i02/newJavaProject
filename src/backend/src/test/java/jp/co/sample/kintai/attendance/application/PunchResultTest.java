package jp.co.sample.kintai.attendance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 打刻の結果が持つ不変条件（UT-ATT-21〜23）。
 *
 * <p><strong>強制している当の場所に、破れた値を渡す。</strong>
 * 通常の経路から作ったインスタンスに対して確かめても、
 * 破れていれば生成の時点で例外になるのでアサーションが恒真になる
 * （CLAUDE.md 落とし穴 36）。
 */
@DisplayName("打刻の結果")
class PunchResultTest {

    private static final LocalDate WORK_DATE = LocalDate.of(2026, 4, 6);

    /**
     * <strong>今まさに打刻した日を未退勤の警告に含めない。</strong>
     * 出勤した直後は当然まだ退勤していないので、
     * 含めると毎回「退勤打刻がありません」と出て警告が意味を失う。
     */
    @Test
    @DisplayName("UT-ATT-21 打刻した勤務日を未退勤の警告に含められない")
    void currentWorkDateCannotBeWarned() {
        assertThatThrownBy(() -> new PunchResult(WORK_DATE,
                PunchResult.CalculationStatus.NOT_CLOSED, Optional.empty(),
                List.of(WORK_DATE.minusDays(1), WORK_DATE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("打刻した勤務日を未退勤の警告に含めません");
    }

    @Test
    @DisplayName("UT-ATT-22 過去の勤務日は警告に含められる")
    void pastWorkDatesAreWarned() {
        var result = new PunchResult(WORK_DATE,
                PunchResult.CalculationStatus.NOT_CLOSED, Optional.empty(),
                List.of(WORK_DATE.minusDays(3), WORK_DATE.minusDays(1)));

        assertThat(result.unclosedWorkDates())
                .containsExactly(WORK_DATE.minusDays(3), WORK_DATE.minusDays(1));
    }

    /** 計算の成否と日次勤怠の有無が食い違う状態は作れない。 */
    @Test
    @DisplayName("UT-ATT-23 計算できたのに日次勤怠が無い状態は作れない")
    void calculatedRequiresAttendance() {
        assertThatThrownBy(() -> new PunchResult(WORK_DATE,
                PunchResult.CalculationStatus.CALCULATED, Optional.empty(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("計算できたのに日次勤怠がありません");
    }
}
