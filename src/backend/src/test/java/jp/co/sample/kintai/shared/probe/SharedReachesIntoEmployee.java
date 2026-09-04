package jp.co.sample.kintai.shared.probe;

import jp.co.sample.kintai.employee.domain.EmployeeNumber;

/**
 * AR-10 違反。{@code shared} が {@code employee} を知っている状態。
 *
 * <p>AR-10 は 4 つのコンテキストを禁じている。
 * <strong>コンテキストごとに違反クラスを置かないと、
 * 禁止先を 4 個から 1 個に削っても自己検査が通る。</strong>
 */
public class SharedReachesIntoEmployee {

    public EmployeeNumber number() {
        return new EmployeeNumber("E0001");
    }
}
