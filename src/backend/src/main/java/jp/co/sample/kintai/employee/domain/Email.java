package jp.co.sample.kintai.employee.domain;

import java.util.Locale;

import jp.co.sample.kintai.shared.domain.BusinessRuleViolationException;

/**
 * メールアドレス。通知の宛先に使う。<strong>認証 ID には使わない</strong>（{@link EmployeeNumber}）。
 *
 * <p><strong>小文字に正規化して保持する。</strong>
 * DB 側の一意制約も {@code lower(email)} に張っているので、
 * ここで正規化しないと「アプリでは別物・DB では同一」という食い違いが起きる。
 * 大文字小文字の違いで同一人物が二重登録されるのを防ぐ。
 */
public record Email(String value) {

    public Email {
        if (value == null) {
            throw new IllegalArgumentException("メールアドレスに null は許されません");
        }
        String trimmed = value.trim();
        // 厳密な RFC 5322 の検証はしない。誤りは送信時にしか分からないため、
        // ここでは「明らかにメールでない値」だけを弾く
        if (!trimmed.contains("@") || trimmed.startsWith("@") || trimmed.endsWith("@")
                || trimmed.length() > 255) {
            throw new BusinessRuleViolationException("要件 2.3",
                    "メールアドレスの形式が不正です: " + value);
        }
        value = trimmed.toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return value;
    }
}
