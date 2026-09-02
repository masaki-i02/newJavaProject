package jp.co.sample.kintai.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 壁掛け時計時刻と絶対時刻の変換。
 *
 * <p><strong>「変換はこの型だけが行う」と宣言した以上、ここが唯一の防波堤である。</strong>
 * ずれても値は「もっともらしい時刻」になるので、テストが無ければ気づけない
 * （CLAUDE.md 落とし穴 1）。
 */
@DisplayName("BusinessZone（業務上のタイムゾーン）")
class BusinessZoneTest {

    @Test
    @DisplayName("Asia/Tokyo に固定されている")
    void zoneIsFixed() {
        assertThat(BusinessZone.ID.getId()).isEqualTo("Asia/Tokyo");
    }

    /** UTC の 00:00 は日本時間の 09:00。日付も 1 日進む。 */
    @Test
    @DisplayName("UTC の絶対時刻を壁掛け時計時刻に直すと 9 時間進む")
    void toLocalFromOffsetDateTime() {
        var absolute = OffsetDateTime.parse("2026-04-05T15:00:00Z");

        assertThat(BusinessZone.toLocal(absolute))
                .isEqualTo(LocalDateTime.parse("2026-04-06T00:00"));
    }

    @Test
    @DisplayName("Instant からも同じ結果になる")
    void toLocalFromInstant() {
        assertThat(BusinessZone.toLocal(Instant.parse("2026-04-05T15:00:00Z")))
                .isEqualTo(LocalDateTime.parse("2026-04-06T00:00"));
    }

    /** 深夜帯の 22:00 は、絶対時刻では前日の 13:00Z である。 */
    @Test
    @DisplayName("壁掛け時計時刻を絶対時刻に直すと +09:00 が付く")
    void toAbsolute() {
        var absolute = BusinessZone.toAbsolute(LocalDateTime.parse("2026-04-06T22:00"));

        assertThat(absolute.getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(absolute.toInstant()).isEqualTo(Instant.parse("2026-04-06T13:00:00Z"));
    }

    /** 日本標準時に夏時間は無いので、往復して値が変わらない。 */
    @Test
    @DisplayName("往復しても値が変わらない")
    void roundTrip() {
        var summer = LocalDateTime.parse("2026-07-15T02:30");
        var winter = LocalDateTime.parse("2026-01-15T02:30");

        assertThat(BusinessZone.toLocal(BusinessZone.toAbsolute(summer))).isEqualTo(summer);
        assertThat(BusinessZone.toLocal(BusinessZone.toAbsolute(winter))).isEqualTo(winter);
    }
}
