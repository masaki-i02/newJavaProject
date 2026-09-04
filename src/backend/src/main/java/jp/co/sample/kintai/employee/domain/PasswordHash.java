package jp.co.sample.kintai.employee.domain;

import java.util.regex.Pattern;

/**
 * ハッシュ化されたパスワード。
 *
 * <p><strong>平文をこの型に入れられないようにする。</strong>
 * 形式を compact constructor で検証するので、
 * ハッシュ化を忘れた文字列は<strong>存在した時点で例外</strong>になる。
 * DB 側の {@code employee_credentials_hash_format_check} と同じ形を要求しており、
 * アプリケーションと DB の 2 か所で平文の保存を拒む。
 *
 * <p>ドメインは BCrypt を知らない。知っているのは
 * <strong>「その形をしていること」</strong>だけである。
 * 実際にハッシュ化と照合を行うのは {@link PasswordHasher} の実装（{@code infrastructure}）。
 */
public record PasswordHash(String value) {

    /** BCrypt の出力。{@code $2a$} / {@code $2b$} / {@code $2y$} で始まる 60 文字。 */
    private static final Pattern FORMAT = Pattern.compile("^\\$2[aby]\\$.{56}$");

    public PasswordHash {
        if (value == null) {
            throw new IllegalArgumentException("パスワードハッシュに null は許されません");
        }
        if (!FORMAT.matcher(value).matches()) {
            // ★ 値そのものを例外に載せない。ログに流れると総当たりの手がかりになる
            throw new IllegalArgumentException(
                    "パスワードハッシュの形式が不正です（長さ " + value.length() + "）");
        }
    }

    /** ログや例外に平文同然のものを出さない。 */
    @Override
    public String toString() {
        return "PasswordHash[********]";
    }
}
