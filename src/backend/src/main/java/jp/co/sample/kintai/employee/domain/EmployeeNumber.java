package jp.co.sample.kintai.employee.domain;

import java.util.regex.Pattern;

import jp.co.sample.kintai.shared.domain.BusinessRuleViolationException;

/**
 * 社員番号。<strong>認証 ID を兼ねる。</strong>
 *
 * <p>メールアドレスを認証 ID にしない。退職者のメールは再割り当てされうるので、
 * 過去の勤怠・承認履歴の主体が別人に化ける（要件定義書 7 章）。
 *
 * <p>比較は大文字小文字を区別する。表記ゆれを許すと、
 * 同一人物が二重に登録されたことに気づけない。
 */
public record EmployeeNumber(String value) {

    private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9]{1,20}$");

    public EmployeeNumber {
        if (value == null) {
            throw new IllegalArgumentException("社員番号に null は許されません");
        }
        if (!FORMAT.matcher(value).matches()) {
            throw new BusinessRuleViolationException("要件 7",
                    "社員番号は英数字 1〜20 文字である必要があります: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
