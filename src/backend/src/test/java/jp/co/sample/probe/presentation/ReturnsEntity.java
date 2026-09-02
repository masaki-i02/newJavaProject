package jp.co.sample.probe.presentation;

import jp.co.sample.probe.infrastructure.ProbeAdapter;

/** AR-04 違反。presentation が infrastructure を直接触っている。 */
public class ReturnsEntity {

    private final ProbeAdapter adapter = new ProbeAdapter();

    public String read() {
        return adapter.load();
    }
}
