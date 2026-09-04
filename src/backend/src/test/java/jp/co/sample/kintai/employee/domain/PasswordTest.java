package jp.co.sample.kintai.employee.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * パスワードの型（UT-PWD-01〜04 / BR-13）。
 *
 * <p><strong>規則を型に持たせる。</strong>
 * コントローラの注釈だけで検証すると、別の経路（初期発行・再設定）から
 * 規則を満たさないパスワードが入る。
 */
@DisplayName("パスワードの型（BR-13）")
class PasswordTest {

    /** BCrypt の出力の一例。照合はしないので値の中身に意味は無い。 */
    private static final String BCRYPT =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    @Nested
    @DisplayName("設定するパスワード")
    class Raw {

        @Test
        @DisplayName("UT-PWD-01 11 文字では生成できない")
        void tooShort() {
            assertThatThrownBy(() -> new RawPassword("a".repeat(11)))
                    .isInstanceOf(RawPassword.WeakPasswordException.class)
                    .hasMessageContaining("12 文字以上");
            assertThatCode(() -> new RawPassword("a".repeat(12)))
                    .doesNotThrowAnyException();
        }

        /**
         * <strong>BCrypt は 72 バイトを超えた分を黙って切り捨てる。</strong>
         * 上限を置かないと、利用者は長い文を設定したつもりで
         * 実際には先頭 72 バイトしか効いていない状態になる。
         *
         * <p>日本語は UTF-8 で 1 文字 3 バイトなので、24 文字で 72 バイト、
         * 25 文字で 75 バイトになる。<strong>文字数で数えていると通ってしまう。</strong>
         */
        @Test
        @DisplayName("UT-PWD-02 UTF-8 で 72 バイトを超えると生成できない")
        void tooLongInBytes() {
            assertThatCode(() -> new RawPassword("あ".repeat(24)))
                    .as("24 文字 = 72 バイト。ちょうど上限").doesNotThrowAnyException();
            assertThatThrownBy(() -> new RawPassword("あ".repeat(25)))
                    .isInstanceOf(RawPassword.WeakPasswordException.class)
                    .hasMessageContaining("72 バイト");
        }

        @Test
        @DisplayName("空白だけでは生成できない")
        void blank() {
            assertThatThrownBy(() -> new RawPassword("            "))
                    .isInstanceOf(RawPassword.WeakPasswordException.class);
        }

        /** 業務上の規則なので 422 になる分類を持つ。 */
        @Test
        @DisplayName("規則違反は業務エラーとして分類される")
        void classifiedAsRuleViolation() {
            assertThatThrownBy(() -> new RawPassword("short"))
                    .isInstanceOf(jp.co.sample.kintai.shared.domain.DomainException.class)
                    .extracting(e -> ((jp.co.sample.kintai.shared.domain.DomainException) e)
                            .kind())
                    .isEqualTo(jp.co.sample.kintai.shared.domain.DomainErrorKind
                            .RULE_VIOLATION);
        }
    }

    @Nested
    @DisplayName("ハッシュ")
    class Hash {

        /**
         * <strong>平文を型に入れられないようにする。</strong>
         * ハッシュ化を忘れた文字列は、存在した時点で例外になる。
         */
        @Test
        @DisplayName("UT-PWD-03 BCrypt の形をしていない値では生成できない")
        void rejectsPlainText() {
            assertThatThrownBy(() -> new PasswordHash("correct-horse-battery"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("形式が不正");
            assertThatThrownBy(() -> new PasswordHash("$2a$10$tooshort"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatCode(() -> new PasswordHash(BCRYPT)).doesNotThrowAnyException();
        }

        /** 例外に値そのものを載せない。ログへ流れると総当たりの手がかりになる。 */
        @Test
        @DisplayName("例外のメッセージに値そのものを載せない")
        void doesNotLeakTheValueInExceptions() {
            assertThatThrownBy(() -> new PasswordHash("correct-horse-battery"))
                    .hasMessageNotContaining("correct-horse-battery");
        }
    }

    @Nested
    @DisplayName("表示")
    class Masking {

        /**
         * <strong>{@code record} の既定の {@code toString()} は値を出す。</strong>
         * ログや例外のメッセージにそのまま乗るので、3 つとも伏せる。
         */
        @Test
        @DisplayName("UT-PWD-04 toString は値を出さない")
        void masked() {
            assertThat(new RawPassword("correct-horse-battery").toString())
                    .doesNotContain("correct-horse-battery").contains("*");
            assertThat(new PasswordAttempt("correct-horse-battery").toString())
                    .doesNotContain("correct-horse-battery").contains("*");
            assertThat(new PasswordHash(BCRYPT).toString())
                    .doesNotContain(BCRYPT).contains("*");
        }
    }

    @Nested
    @DisplayName("照合する値と設定する値")
    class AttemptVersusRaw {

        /**
         * <strong>照合される値には規則を当てない。</strong>
         * ログイン時に短いパスワードを入れただけで 422 が返ると、
         * 「この社員番号は存在して、入力が短かっただけ」と読める応答になる。
         */
        @Test
        @DisplayName("入力されたパスワードは短くても生成できる")
        void attemptAcceptsAnything() {
            assertThatCode(() -> new PasswordAttempt("a")).doesNotThrowAnyException();
            assertThatCode(() -> new PasswordAttempt("")).doesNotThrowAnyException();
        }
    }
}
