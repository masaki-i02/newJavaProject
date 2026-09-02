package jp.co.sample.probe.application;

import jp.co.sample.probe.infrastructure.ProbeAdapter;

/** AR-03 違反。application がポートではなく実装を知っている。 */
public class DependsOnAdapter {

    private final ProbeAdapter adapter = new ProbeAdapter();

    public String read() {
        return adapter.load();
    }
}
