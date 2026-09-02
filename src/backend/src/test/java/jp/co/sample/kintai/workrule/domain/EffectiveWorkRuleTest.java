package jp.co.sample.kintai.workrule.domain;

import static jp.co.sample.kintai.support.WorkRules.fixed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /** 別の社員が使っている、まったく別の系列。 */
    private static final WorkRuleSeriesId OTHER_SERIES = new WorkRuleSeriesId(UUID.randomUUID());

    private static WorkRule version(DateRange validPeriod) {
        return version(SERIES, validPeriod);
    }

    private static WorkRule version(WorkRuleSeriesId seriesId, DateRange validPeriod) {
        return new WorkRule(new WorkRuleId(UUID.randomUUID()), seriesId, validPeriod, fixed(),
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

    /**
     * 版の一覧は<strong>系列で絞られていなければならない。</strong>
     * 絞りを落としても、系列が 1 つしかないテストでは気づけない。
     * 他社員の系列の版を混ぜ、しかも先に並べておく。
     */
    @Test
    @DisplayName("他の系列の版は、有効期間が重なっていても返らない")
    void versionsOfAnotherSeriesAreIgnored() {
        var foreign = version(OTHER_SERIES, DateRange.startingAt(LocalDate.of(2026, 4, 1)));

        assertThat(EffectiveWorkRule.resolve(ASSIGNMENTS, List.of(foreign, BEFORE, AFTER),
                LocalDate.of(2026, 5, 5))).contains(BEFORE);
    }

    /**
     * 期間の重なりは DB の {@code EXCLUDE} 制約で禁じている。
     * 2 件見つかるのは制約が壊れたか、別の社員の履歴を混ぜて渡したかである。
     * <strong>先頭を黙って採ると、どちらが使われたかが実行のたびに変わりうる。</strong>
     */
    @Test
    @DisplayName("同じ系列の版が重なっていたら例外になる（黙って先頭を採らない）")
    void overlappingVersionsAreRejected() {
        var overlapping = version(new DateRange(
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 12, 1)));

        assertThatThrownBy(() -> EffectiveWorkRule.resolve(ASSIGNMENTS,
                List.of(BEFORE, overlapping), LocalDate.of(2026, 5, 5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("版 が 2026-05-05 時点で 2 件あります");
    }

    @Test
    @DisplayName("適用が重なっていたら例外になる")
    void overlappingAssignmentsAreRejected() {
        var duplicated = List.of(
                new WorkRuleAssignment(EMPLOYEE, SERIES,
                        DateRange.startingAt(LocalDate.of(2026, 4, 1))),
                new WorkRuleAssignment(EMPLOYEE, OTHER_SERIES,
                        DateRange.startingAt(LocalDate.of(2026, 4, 1))));

        assertThatThrownBy(() -> EffectiveWorkRule.resolve(duplicated, List.of(BEFORE, AFTER),
                LocalDate.of(2026, 5, 5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("適用 が 2026-05-05 時点で 2 件あります");
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
