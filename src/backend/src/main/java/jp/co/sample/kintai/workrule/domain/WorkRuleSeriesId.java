package jp.co.sample.kintai.workrule.domain;

import java.util.UUID;

/**
 * 就業規則の<strong>系列</strong>の識別子。改定をまたいで変わらない。
 *
 * <p>社員への適用はこちらを指す。版を指すと、改定した瞬間に全社員の規則が
 * 「未設定」になる（ADR 0003）。
 */
public record WorkRuleSeriesId(UUID value) {

    public WorkRuleSeriesId {
        if (value == null) {
            throw new IllegalArgumentException("就業規則の系列の識別子に null は許されません");
        }
    }

    public static WorkRuleSeriesId of(String value) {
        return new WorkRuleSeriesId(UUID.fromString(value));
    }
}
