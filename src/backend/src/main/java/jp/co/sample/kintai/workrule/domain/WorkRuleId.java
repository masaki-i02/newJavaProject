package jp.co.sample.kintai.workrule.domain;

import java.util.UUID;

/**
 * 就業規則の<strong>版</strong>の識別子。
 *
 * <p>{@link WorkRuleSeriesId} と別の型にしてある。どちらも UUID だが、
 * 同じ型だと取り違えてもコンパイルが通ってしまう（CLAUDE.md 落とし穴 14）。
 */
public record WorkRuleId(UUID value) {

    public WorkRuleId {
        if (value == null) {
            throw new IllegalArgumentException("就業規則の版の識別子に null は許されません");
        }
    }

    public static WorkRuleId of(String value) {
        return new WorkRuleId(UUID.fromString(value));
    }
}
