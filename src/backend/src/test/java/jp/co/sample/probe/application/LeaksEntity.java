package jp.co.sample.probe.application;

import jp.co.sample.probe.infrastructure.ProbeEntity;

/** AR-05 違反。JPA エンティティが infrastructure の外に出ている。 */
public class LeaksEntity {

    public ProbeEntity entity() {
        return new ProbeEntity();
    }
}
