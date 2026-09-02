package jp.co.sample.kintai.shared.domain;

import java.util.UUID;

/**
 * 社員の識別子。
 *
 * <p>UUID をそのまま持ち回さない。同じ UUID でも意味が違うなら型を分ける
 * （CLAUDE.md 落とし穴 14）。
 */
public record EmployeeId(UUID value) {

    public EmployeeId {
        if (value == null) {
            throw new IllegalArgumentException("社員の識別子に null は許されません");
        }
    }

    public static EmployeeId of(String value) {
        return new EmployeeId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
