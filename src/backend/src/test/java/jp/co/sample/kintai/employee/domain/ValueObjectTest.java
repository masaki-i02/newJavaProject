package jp.co.sample.kintai.employee.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.shared.domain.BusinessRuleViolationException;

/** 社員・組織の値オブジェクト。 */
@DisplayName("社員・組織の値オブジェクト")
class ValueObjectTest {

    @Nested
    @DisplayName("社員番号")
    class Number {

        @Test
        @DisplayName("英数字 1〜20 文字なら作れる")
        void accepted() {
            assertThat(new EmployeeNumber("E0001").value()).isEqualTo("E0001");
            assertThat(new EmployeeNumber("A").value()).isEqualTo("A");
            assertThat(new EmployeeNumber("A2345678901234567890").value()).hasSize(20);
        }

        @Test
        @DisplayName("21 文字以上は作れない")
        void tooLong() {
            assertThatThrownBy(() -> new EmployeeNumber("A23456789012345678901"))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("英数字 1〜20 文字");
        }

        @Test
        @DisplayName("空文字は作れない")
        void empty() {
            assertThatThrownBy(() -> new EmployeeNumber(""))
                    .isInstanceOf(BusinessRuleViolationException.class);
        }

        @Test
        @DisplayName("記号を含むと作れない")
        void withSymbol() {
            assertThatThrownBy(() -> new EmployeeNumber("E-0001"))
                    .isInstanceOf(BusinessRuleViolationException.class);
        }
    }

    @Nested
    @DisplayName("メールアドレス")
    class Mail {

        /**
         * <strong>小文字に正規化する。</strong>
         * DB 側の一意制約も {@code lower(email)} に張っているので、
         * ここで正規化しないと「アプリでは別物・DB では同一」という食い違いが起きる。
         */
        @Test
        @DisplayName("小文字に正規化され、大文字違いは等しくなる")
        void normalizedToLowerCase() {
            assertThat(new Email("Taro.Yamada@Example.COM"))
                    .isEqualTo(new Email("taro.yamada@example.com"));
            assertThat(new Email("Taro@Example.com").value()).isEqualTo("taro@example.com");
        }

        @Test
        @DisplayName("前後の空白は落とす")
        void trimmed() {
            assertThat(new Email("  taro@example.com  ").value())
                    .isEqualTo("taro@example.com");
        }

        @Test
        @DisplayName("@ を含まないと作れない")
        void withoutAtMark() {
            assertThatThrownBy(() -> new Email("taro.example.com"))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("メールアドレスの形式が不正です");
        }

        @Test
        @DisplayName("@ で始まる・終わる値は作れない")
        void danglingAtMark() {
            assertThatThrownBy(() -> new Email("@example.com"))
                    .isInstanceOf(BusinessRuleViolationException.class);
            assertThatThrownBy(() -> new Email("taro@"))
                    .isInstanceOf(BusinessRuleViolationException.class);
        }
    }

    @Nested
    @DisplayName("識別子")
    class Identifiers {

        /**
         * 社員 ID と部署 ID は別の型である。
         * 同じ UUID でも意味が違うなら型を分ける（CLAUDE.md 落とし穴 14）。
         */
        @Test
        @DisplayName("null では作れない")
        void nullIsRejected() {
            assertThatThrownBy(() -> new DepartmentId(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("部署 ID に null は許されません");
        }

        @Test
        @DisplayName("部署コードは英数字 1〜20 文字")
        void departmentCode() {
            assertThat(new DepartmentCode("D001").value()).isEqualTo("D001");
            assertThatThrownBy(() -> new DepartmentCode("営業部"))
                    .isInstanceOf(BusinessRuleViolationException.class);
        }
    }
}
