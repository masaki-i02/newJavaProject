package jp.co.sample.probe.domain;

import jp.co.sample.probe.infrastructure.ProbeAdapter;

/** AR-02 違反。ドメインが外側の層を知っている。 */
public class ReachesIntoInfrastructure {

    private final ProbeAdapter adapter = new ProbeAdapter();

    public String read() {
        return adapter.load();
    }
}
