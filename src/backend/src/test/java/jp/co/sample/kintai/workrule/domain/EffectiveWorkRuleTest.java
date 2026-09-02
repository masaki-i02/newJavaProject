package jp.co.sample.kintai.workrule.domain;

import static jp.co.sample.kintai.support.WorkRules.fixed;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/** 就業規則の時点解決（UT-WR-13〜15）。 */
@DisplayName("就業規則の時点解決")
class EffectiveWorkRuleTest {

    private static final EmployeeId EMPLOYEE = new EmployeeId(UUID.randomUUID());
    private static final WorkRuleSeriesId SERIES = new WorkRuleSeriesId(UUID.randomUUID());

    private static final WorkRule BEFORE = version(
            new DateRange(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 10, 1)));
    private static final WorkRule AFTER = version(
            DateRange.startingAt(LocalDate.of(2026, 10, 1)));

    /** 社員は系列に紐づく。改定しても適用行は書き換えない。 */
    private static final List<WorkRuleAssignment> ASSIGNMENTS = List.of(
            new WorkRuleAssignment(EMPLOYEE, SERIES,
                    DateRange.startingAt(LocalDate.of(2026, 4, 1))));

    private static WorkRule version(DateRange validPeriod) {
        return new WorkRule(new WorkRuleId(UUID.randomUUID()), SERIES, validPeriod, fixed(),
                Duration.ofHours(8), Duration.ofHours(40),
                NightWindow.STANDARD, PremiumRates.STATUTORY);
    }

    @Test
    @DisplayName("UT-WR-13 改定後の日付では新しい版が返る。適用行は書き換えていない")
    void afterRevision() {
        assertThat(EffectiveWorkRule.resolve(ASSIGNMENTS, List.of(BEFORE, AFTER),
                LocalDate.of(2026, 11, 5))).contains(AFTER);
    }

    @Test
    @DisplayName("UT-WR-14 改定前の日付では古い版が返る（過去分を当時の規則で再計算できる）")
    void beforeRevision() {
        assertThat(EffectiveWorkRule.resolve(ASSIGNMENTS, List.of(BEFORE, AFTER),
                LocalDate.of(2026, 5, 5))).contains(BEFORE);
    }

    /**
     * 版に隙間があるのは運用の誤りだが、<strong>例外にしない。</strong>
     * 呼び出し側が「就業規則未設定」として扱い、未適用者の一覧で検知する。
     */
    @Test
    @DisplayName("UT-WR-15 版に隙間がある日付では空が返る（例外にしない）")
    void gapBetweenVersions() {
        var withGap = version(DateRange.startingAt(LocalDate.of(2026, 11, 1)));

        assertThat(EffectiveWorkRule.resolve(ASSIGNMENTS, List.of(BEFORE, withGap),
                LocalDate.of(2026, 10, 15))).isEmpty();
    }

    @Test
    @DisplayName("適用そのものが無い日付では空が返る")
    void beforeAssignment() {
        assertThat(EffectiveWorkRule.resolve(ASSIGNMENTS, List.of(BEFORE, AFTER),
                LocalDate.of(2026, 3, 1))).isEmpty();
    }

    @Test
    @DisplayName("改定の境界日は新しい版が返る（半開区間）")
    void boundaryDayBelongsToTheNewVersion() {
        assertThat(EffectiveWorkRule.resolve(ASSIGNMENTS, List.of(BEFORE, AFTER),
                LocalDate.of(2026, 10, 1))).contains(AFTER);
        assertThat(EffectiveWorkRule.resolve(ASSIGNMENTS, List.of(BEFORE, AFTER),
                LocalDate.of(2026, 9, 30))).contains(BEFORE);
    }
}
