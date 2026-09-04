package jp.co.sample.kintai.employee.domain;

/**
 * 照合のために入力されたパスワード。
 *
 * <p><strong>{@link RawPassword} と分ける。</strong>
 * 「これから設定する値」は規則（BR-13）を満たさなければならないが、
 * 「入力されて照合される値」は<strong>どんな文字列でもありうる</strong>。
 * 同じ型にすると、ログイン時に短いパスワードを入れただけで
 * 「パスワードが規則を満たしていません（422）」が返り、
 * <strong>認証の失敗理由を区別して返さない</strong>という決めごとが崩れる。
 */
public record PasswordAttempt(String value) {

    public PasswordAttempt {
        if (value == null) {
            throw new IllegalArgumentException("入力されたパスワードに null は許されません");
        }
    }

    /** ログや例外に平文を出さない。 */
    @Override
    public String toString() {
        return "PasswordAttempt[********]";
    }
}
