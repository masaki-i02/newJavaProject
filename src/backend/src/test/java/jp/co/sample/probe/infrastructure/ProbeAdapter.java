package jp.co.sample.probe.infrastructure;

/** 内側の層から参照されてはいけない実装。 */
public class ProbeAdapter {

    public String load() {
        return "probe";
    }
}
