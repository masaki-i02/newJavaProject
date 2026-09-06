package jp.co.sample.kintai.workrule.domain;

import jp.co.sample.kintai.shared.domain.DateRange;

/**
 * ある期間に就業規則の系列が実際に適用されていた範囲。
 *
 * <p><strong>「年度に重なる系列」だけでは足りない。</strong>
 * 年度の途中で新設した系列（10 月からフレックスを導入した、など）は
 * 4 月〜9 月に版を持たないのが正常であり、
 * 系列の一覧だけを見て年度の全所定労働日について版を要求すると、
 * <strong>その年度の給与連携が永久に出せなくなる</strong>（CLAUDE.md 落とし穴 131）。
 *
 * <p>社員は載せない。割増賃金の基礎額の分母（労基則 19 条 1 項 4 号）に要るのは
 * 「その日、会社の所定が何時間だったか」であって、誰に適用されていたかではない。
 *
 * @param seriesId 就業規則の系列
 * @param period   適用されていた期間。半開区間
 */
public record WorkRuleSeriesUsage(WorkRuleSeriesId seriesId, DateRange period) {

    public WorkRuleSeriesUsage {
        if (seriesId == null || period == null) {
            throw new IllegalArgumentException("就業規則の適用範囲の項目に null は許されません");
        }
    }
}
