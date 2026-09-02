package jp.co.sample.kintai.shared.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 業務上のタイムゾーン（アーキテクチャ設計書 6.3）。
 *
 * <p>ドメインは<strong>壁掛け時計の時刻</strong>（{@link LocalDateTime}）を扱う。
 * 始業 9:00 や深夜帯 22:00 は「その時刻の表示そのもの」であって絶対時刻ではない。
 * 一方 DB は {@code timestamptz}（絶対時刻）で持つ。
 *
 * <p><strong>両者の変換はこの型だけが行う。</strong>
 * 変換をあちこちに書くと、どこか 1 か所が抜けただけで深夜帯や日跨ぎの判定がずれる。
 * ずれても値は「もっともらしい時刻」なので、テストが無ければ気づけない。
 *
 * <p>架空企業「株式会社サンプル」は単一事業所なので、ゾーンは 1 つに固定する。
 * 設定で変えられるようにはしない。変えられると、過去に記録した打刻の解釈まで
 * 遡って変わってしまう。
 */
public final class BusinessZone {

    /** {@code Asia/Tokyo}。 */
    public static final ZoneId ID = ZoneId.of("Asia/Tokyo");

    private BusinessZone() {
    }

    /** 絶対時刻 → 壁掛け時計の時刻。 */
    public static LocalDateTime toLocal(OffsetDateTime absolute) {
        return absolute.atZoneSameInstant(ID).toLocalDateTime();
    }

    /** 絶対時刻 → 壁掛け時計の時刻。 */
    public static LocalDateTime toLocal(Instant absolute) {
        return LocalDateTime.ofInstant(absolute, ID);
    }

    /**
     * 壁掛け時計の時刻 → 絶対時刻。
     *
     * <p>日本標準時に夏時間は無いので、存在しない時刻や曖昧な時刻は生じない。
     */
    public static OffsetDateTime toAbsolute(LocalDateTime local) {
        return local.atZone(ID).toOffsetDateTime();
    }
}
