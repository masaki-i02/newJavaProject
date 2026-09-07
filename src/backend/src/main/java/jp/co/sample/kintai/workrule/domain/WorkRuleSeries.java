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
 * @param version     楽観ロックの版。改定のたびに 1 つ進む。
 *                    <strong>保存前は 0 で、保存された系列は DB の値を持つ。</strong>
 *
 *                    <p>月次勤怠は 1 から始めると決めた（落とし穴 57）が、
 *                    こちらは 0 から始めてよい。あちらの危険は
 *                    「行が無い月」を画面が版 0 として握れることにあり、
 *                    <strong>系列は取得しないと識別子が分からない</strong>ので
 *                    存在しない系列の版を握ることがない。
 *                    存在しなければ改定は版を見る前に 404 で終わる。
 */
public record WorkRuleSeries(WorkRuleSeriesId id, String name,
                             Optional<LocalDate> abolishedOn, long version) {

    public WorkRuleSeries {
        if (id == null || name == null || abolishedOn == null) {
            throw new IllegalArgumentException("就業規則の系列の項目に null は許されません");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("就業規則の名称は必須です");
        }
    }

    /** まだ保存していない系列。版は保存したときに DB が決める。 */
    public static WorkRuleSeries active(WorkRuleSeriesId id, String name) {
        return new WorkRuleSeries(id, name, Optional.empty(), 0);
    }

    /** 系列が有効な期間。半開区間。 */
    public DateRange activePeriod() {
        return new DateRange(DateRange.UNBOUNDED_START, abolishedOn.orElse(DateRange.UNBOUNDED_END));
    }

    public boolean isActiveOn(LocalDate date) {
        return activePeriod().contains(date);
    }
}
