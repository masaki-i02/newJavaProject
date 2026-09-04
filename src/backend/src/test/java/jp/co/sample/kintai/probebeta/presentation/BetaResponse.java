package jp.co.sample.kintai.probebeta.presentation;

/**
 * 他コンテキストから参照されてはいけない表現。
 *
 * <p>AR-06 は {@code infrastructure} と {@code presentation} の
 * <strong>2 つ</strong>を禁じている。
 * {@code infrastructure} 側だけを踏む違反クラスしか用意しないと、
 * <strong>禁止先から {@code presentation} を削ったルールが通ってしまう</strong>
 * （アーキテクチャ設計書 9「条件ごとに違反クラスを 1 つずつ置く」）。
 */
public class BetaResponse {

    public String render() {
        return "beta";
    }
}
