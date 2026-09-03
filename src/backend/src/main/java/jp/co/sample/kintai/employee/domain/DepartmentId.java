package jp.co.sample.kintai.employee.domain;

import java.util.UUID;

/**
 * 部署の識別子。
 *
 * <p><strong>UUID の生値で持ち回らない。</strong>
 * {@code findById(UUID)} は社員 ID でも部署 ID でも通ってしまうが、
 * この型なら取り違えがコンパイルエラーになる（CLAUDE.md 落とし穴 14）。
 */
public record DepartmentId(UUID value) {

    public DepartmentId {
        if (value == null) {
            throw new IllegalArgumentException("部署 ID に null は許されません");
        }
    }

    public static DepartmentId of(String uuid) {
        return new DepartmentId(UUID.fromString(uuid));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
