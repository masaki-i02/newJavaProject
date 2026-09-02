package jp.co.sample.probe.infrastructure;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/**
 * 名前の規約から外れた JPA エンティティ。
 *
 * <p>{@code *Entity} で終わらないので、名前だけを見るルールはこれを見逃す。
 * <strong>注釈による判定が効いているかは、この 1 個でしか確かめられない。</strong>
 */
@Entity
public class LegacyRow {

    @Id
    private Long id;

    public Long getId() {
        return id;
    }
}
