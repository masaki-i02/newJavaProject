package jp.co.sample.probe.application;

import jp.co.sample.probe.infrastructure.LegacyRow;
import jp.co.sample.probe.infrastructure.ProbeEntity;

/**
 * AR-05 違反。JPA エンティティが infrastructure の外に出ている。
 *
 * <p>名前の規約に沿ったもの（{@link ProbeEntity}）と、
 * 注釈だけを持つもの（{@link LegacyRow}）の<strong>両方</strong>に依存する。
 * ルールがどちらか一方の条件を失ったら、失ったほうが報告されなくなる。
 */
public class LeaksEntity {

    public ProbeEntity named() {
        return new ProbeEntity();
    }

    public LegacyRow annotated() {
        return new LegacyRow();
    }
}
