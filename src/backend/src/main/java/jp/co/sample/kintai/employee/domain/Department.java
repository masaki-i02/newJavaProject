package jp.co.sample.kintai.employee.domain;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 部署。本部 → 部 → 課のツリーを親への参照で表す。
 *
 * <p><strong>部署そのものは有効期間を持たない。</strong>
 * 組織改編の履歴管理は要件に含まれない（要件定義書 2.3）ので、廃止日だけを持つ。
 *
 * @param id          識別子
 * @param code        部署コード
 * @param name        名称
 * @param parentId    親部署。空ならルート（本部）
 * @param abolishedOn 廃止日。<strong>この日から使えない</strong>（半開区間の上限）
 */
public record Department(DepartmentId id, DepartmentCode code, String name,
                         Optional<DepartmentId> parentId, Optional<LocalDate> abolishedOn) {

    public Department {
        if (id == null || code == null || name == null
                || parentId == null || abolishedOn == null) {
            throw new IllegalArgumentException("部署の項目に null は許されません");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("部署名は必須です");
        }
        // 1 段の循環はここで防ぐ。多段の循環は DB のトリガが防ぐ（CHECK に再帰は書けない）
        if (parentId.filter(id::equals).isPresent()) {
            throw new IllegalArgumentException("部署は自分自身を親にできません: " + id);
        }
    }

    /** 現存するルート部署を作る。 */
    public static Department root(DepartmentId id, DepartmentCode code, String name) {
        return new Department(id, code, name, Optional.empty(), Optional.empty());
    }

    /** 現存する子部署を作る。 */
    public static Department under(DepartmentId id, DepartmentCode code, String name,
                                   DepartmentId parentId) {
        return new Department(id, code, name, Optional.of(parentId), Optional.empty());
    }

    public boolean isRoot() {
        return parentId.isEmpty();
    }

    /**
     * 指定日に現存していたか。
     *
     * <p>廃止日は<strong>その日から使えない</strong>という上限なので、廃止日当日は現存しない。
     * 在籍期間（退職日は最終在籍日）と向きが違うことに注意する。
     */
    public boolean isActiveOn(LocalDate date) {
        return abolishedOn.map(date::isBefore).orElse(true);
    }

    /** 廃止する。 */
    public Department abolish(LocalDate abolishedFrom) {
        return new Department(id, code, name, parentId, Optional.of(abolishedFrom));
    }
}
