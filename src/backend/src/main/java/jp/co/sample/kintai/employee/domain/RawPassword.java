package jp.co.sample.kintai.employee.domain;

import java.io.Serial;
import java.nio.charset.StandardCharsets;

import jp.co.sample.kintai.shared.domain.BusinessRuleViolationException;

/**
 * 利用者が入力した平文のパスワード（BR-13）。
 *
 * <p><strong>強度の検証をここに集める。</strong>
 * コントローラの注釈だけで検証すると、別の経路（初期発行・再設定）から
 * 規則を満たさないパスワードが入る。<strong>型を通らないと登録できない</strong>形にする。
 *
 * <p>長さは<strong>文字数の下限とバイト数の上限</strong>で見る。
 * BCrypt は 72 バイトを超えた分を黙って切り捨てるので、上限を置かないと
 * 利用者は長い文を設定したつもりで実際には先頭 72 バイトしか効いていない状態になる。
 */
public record RawPassword(String value) {

    /** 総当たりへの耐性のための下限（BR-13）。 */
    public static final int MIN_LENGTH = 12;

    /**
     * BCrypt が扱える上限。
     *
     * <p><strong>72 バイトを超えた分は切り捨てられる。</strong>
     * 実装（BCrypt）の都合が業務ルールに現れている数少ない例なので、
     * 由来を明示しておく。
     */
    public static final int MAX_BYTES = 72;

    public RawPassword {
        if (value == null || value.isBlank()) {
            throw new WeakPasswordException("パスワードを入力してください");
        }
        if (value.codePointCount(0, value.length()) < MIN_LENGTH) {
            throw new WeakPasswordException(
                    "パスワードは %d 文字以上にしてください".formatted(MIN_LENGTH));
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new WeakPasswordException(
                    "パスワードが長すぎます（UTF-8 で %d バイトまで）".formatted(MAX_BYTES));
        }
    }

    /** ログや例外に平文を出さない。 */
    @Override
    public String toString() {
        return "RawPassword[********]";
    }

    /**
     * 強度が足りない。
     *
     * <p><strong>入力の形式ではなく業務上の規則</strong>なので 422 になる。
     * 400 にすると「文字数が足りない」を「JSON が壊れている」と同じ扱いにしてしまう。
     */
    public static final class WeakPasswordException extends BusinessRuleViolationException {

        @Serial
        private static final long serialVersionUID = 1L;

        WeakPasswordException(String message) {
            super("BR-13", message);
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:weak-password";
        }

        @Override
        public String title() {
            return "パスワードが規則を満たしていません";
        }
    }
}
