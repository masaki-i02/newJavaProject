package jp.co.sample.kintai.config;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * JSON への変換の設定。
 *
 * <p><strong>日時は秒までで返す。</strong>
 * API 設計書はどの応答例も {@code 2026-04-07T09:00:00} と書いているが、
 * 実際には {@code 2026-09-07T03:49:10.534283} が返っていた。
 * 出どころが 2 つある。
 *
 * <ul>
 *   <li>打刻の時刻 … {@code Clock} から解決した現在時刻（ナノ秒まで持つ）</li>
 *   <li>証跡の時刻 … DB の {@code now()}（マイクロ秒まで持つ）</li>
 * </ul>
 *
 * <p><strong>だから変換の側で 1 か所にそろえる。</strong>
 * 生成元ごとに切り詰めると、片方を直したときにもう片方が古くなる。
 *
 * <p>秒未満を落としてよいのは、<strong>労働時間の集計単位が 1 分だから</strong>である
 * （BR-01）。秒未満は業務上の意味を持たない。
 *
 * <p>これは表示上の問題にとどまらない。フロントエンドは
 * 「オフセットを持たない壁掛け時計時刻」を検証してから使う型を持っており、
 * その検証は小数秒を<strong>拒否する。</strong>
 * 契約どおりの値を返さないと、型の約束どおりに書いた画面が例外で落ちる
 * （CLAUDE.md 落とし穴 87 と同型で、守っているように見えるだけの状態になる）。
 */
@Configuration
public class JsonConfig {

    @Bean
    JacksonModule wallClockSecondsModule() {
        SimpleModule module = new SimpleModule("wall-clock-seconds");
        module.addSerializer(LocalDateTime.class, new SecondsSerializer());
        return module;
    }

    /**
     * 秒までを ISO-8601 で書き出す。
     *
     * <p><strong>{@code toString()} に任せない。</strong>
     * {@code LocalDateTime#toString} は秒が 0 のとき秒そのものを省くので、
     * {@code 09:00:00} が {@code 09:00} になり、
     * 桁数が値によって変わる。受け取る側は必ずどちらかで取り違える。
     */
    private static final class SecondsSerializer extends StdSerializer<LocalDateTime> {

        private static final long serialVersionUID = 1L;

        private static final DateTimeFormatter SECONDS =
                DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

        private SecondsSerializer() {
            super(LocalDateTime.class);
        }

        @Override
        public void serialize(LocalDateTime value, JsonGenerator generator,
                              SerializationContext context) {
            generator.writeString(SECONDS.format(value));
        }
    }
}
