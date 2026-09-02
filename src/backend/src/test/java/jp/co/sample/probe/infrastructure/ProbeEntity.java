package jp.co.sample.probe.infrastructure;

/**
 * 名前の規約（{@code *Entity}）だけを持つクラス。
 *
 * <p>JPA の注釈は<strong>付けない</strong>。付けると、名前の規約と注釈の
 * どちらの判定が効いてルールが落ちたのか区別できなくなる。
 * 注釈による判定は {@link LegacyRow} が担う。
 */
public class ProbeEntity {

    private Long id;

    public Long getId() {
        return id;
    }
}
