package jp.co.sample.kintai.shared.domain;

import java.io.Serial;

/**
 * 法令または就業規則の定めに反する値が入力された。
 *
 * <p><strong>実装の不備ではなく、利用者の入力で踏める誤りである。</strong>
 * 人事が就業規則登録画面から「割増率 0.20」「法定労働時間 12 時間」を入力すれば起きる。
 * これを {@code IllegalArgumentException} のままにすると
 * {@code @RestControllerAdvice} が捕まえられず、業務エラーが 500 になって漏れる
 * （アーキテクチャ設計書 6.2）。
 *
 * <p>判定の基準は「利用者の入力で踏めるか」である。
 * {@code null} や桁あふれのような実装の不備は、引き続き
 * {@code IllegalArgumentException} / {@code IllegalStateException} のままにする。
 */
public class BusinessRuleViolationException extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String rule;

    /**
     * @param rule    違反した業務ルールの識別子（{@code BR-06} など）。Problem Details に載せる
     * @param message 利用者に見せる説明
     */
    public BusinessRuleViolationException(String rule, String message) {
        super(message);
        this.rule = rule;
    }

    /** 違反した業務ルールの識別子。 */
    public String rule() {
        return rule;
    }

    @Override
    public String errorCode() {
        return "urn:kintai:error:business-rule-violation";
    }

    @Override
    public DomainErrorKind kind() {
        return DomainErrorKind.RULE_VIOLATION;
    }

    @Override
    public String title() {
        return "業務上の制約に反しています";
    }
}
