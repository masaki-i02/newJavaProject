package jp.co.sample.kintai.employee.domain;

import java.util.regex.Pattern;

import jp.co.sample.kintai.shared.domain.BusinessRuleViolationException;

/** 部署コード。英数字 1〜20 文字。 */
public record DepartmentCode(String value) {

    private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9]{1,20}$");

    public DepartmentCode {
        if (value == null) {
            throw new IllegalArgumentException("部署コードに null は許されません");
        }
        if (!FORMAT.matcher(value).matches()) {
            throw new BusinessRuleViolationException("要件 2.3",
                    "部署コードは英数字 1〜20 文字である必要があります: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
