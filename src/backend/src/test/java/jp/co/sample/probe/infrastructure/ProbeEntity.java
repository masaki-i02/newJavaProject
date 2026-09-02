package jp.co.sample.probe.infrastructure;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/**
 * infrastructure の外へ漏れてはいけない JPA エンティティ。
 *
 * <p>名前の規約（{@code *Entity}）と {@link Entity} 注釈の両方を持たせて、
 * AR-05 の 2 つの判定条件をどちらも踏ませる。
 */
@Entity
public class ProbeEntity {

    @Id
    private Long id;

    public Long getId() {
        return id;
    }
}
