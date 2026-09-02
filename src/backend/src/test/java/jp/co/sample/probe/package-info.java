/**
 * ArchUnit のルールが<strong>本当に落ちること</strong>を確かめるための、わざと違反したクラス。
 *
 * <p>ルールが通っているとき、その理由は 2 つありうる。
 * <ol>
 *   <li>規約が守られている</li>
 *   <li>そもそも検査対象が 1 件も無い、または条件が誤っていて何にも当たらない</li>
 * </ol>
 * この 2 つを区別しないと、ルールは「常に緑のお守り」になる（CLAUDE.md 落とし穴 31）。
 * {@code ArchRuleSelfTest} が、ここのクラスに対して各ルールが落ちることを確かめる。
 *
 * <p><strong>パッケージを {@code jp.co.sample.kintai} の外に置いている。</strong>
 * Spring Boot のコンポーネント走査と JPA のエンティティ走査は
 * {@code @SpringBootApplication} が付いたクラスのパッケージ配下を対象にする。
 * 内側に置くと、わざと壊したクラスが本番の DI コンテナやマッピングに載ってしまう。
 */
package jp.co.sample.probe;
