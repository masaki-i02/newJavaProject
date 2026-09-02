package jp.co.sample.kintai.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

/**
 * DB の制約が働いたことを検証する。
 *
 * <p><strong>「拒否された」だけを見ない。</strong>
 * 期待した制約で拒否されたかを確認する。別の制約に先に引っかかると、
 * 狙った検証は行われていない（CLAUDE.md 落とし穴 17・25）。
 */
public final class ConstraintAssertions {

    private ConstraintAssertions() {
    }

    /** 指定した名前の制約で拒否されることを確かめる。 */
    public static void rejectedBy(String constraintName, ThrowingCallable action) {
        assertThatThrownBy(action)
                .as("%s で拒否されること", constraintName)
                .hasStackTraceContaining(constraintName);
    }

    /** 制約トリガのメッセージで拒否されることを確かめる。 */
    public static void rejectedWithMessage(String fragment, ThrowingCallable action) {
        assertThatThrownBy(action)
                .as("「%s」というメッセージで拒否されること", fragment)
                .hasStackTraceContaining(fragment);
    }

    /** 拒否されないことを確かめる。正常系は必ず条件ごとに 1 件ずつ立てる。 */
    public static void accepted(ThrowingCallable action) {
        assertThat(runQuietly(action)).as("受け入れられること").isNull();
    }

    private static Throwable runQuietly(ThrowingCallable action) {
        try {
            action.call();
            return null;
        } catch (Throwable t) {
            throw new AssertionError("受け入れられるはずが拒否された: " + rootMessage(t), t);
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}
