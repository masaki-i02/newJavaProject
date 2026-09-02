package jp.co.sample.kintai.workrule.domain;

import java.time.LocalDate;
import java.util.Optional;

import jp.co.sample.kintai.shared.domain.DateRange;

/**
 * 就業規則の<strong>系列</strong>。改定をまたいで変わらない。
 *
 * <p>社員が結びつくのは「標準勤務という規則」であって
 * 「2024 年 4 月版の標準勤務」ではない（ADR 0003）。
 *
 * @param id          系列の識別子
 * @param name        「標準勤務」「フレックス勤務」など。改定しても変わらない
 * @param abolishedOn 廃止日。この日から使えない（半開区間の上限）
 */
public record WorkRuleSeries(WorkRuleSeriesId id, String name,
                             Optional<LocalDate> abolishedOn) {

    public WorkRuleSeries {
        if (id == null || name == null || abolishedOn == null) {
            throw new IllegalArgumentException("就業規則の系列の項目に null は許されません");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("就業規則の名称は必須です");
        }
    }

    public static WorkRuleSeries active(WorkRuleSeriesId id, String name) {
        return new WorkRuleSeries(id, name, Optional.empty());
    }

    /** 系列が有効な期間。半開区間。 */
    public DateRange activePeriod() {
        return new DateRange(DateRange.UNBOUNDED_START, abolishedOn.orElse(DateRange.UNBOUNDED_END));
    }

    public boolean isActiveOn(LocalDate date) {
        return activePeriod().contains(date);
    }
}
