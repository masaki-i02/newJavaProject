package jp.co.sample.kintai.workrule.domain;

import java.time.LocalDate;
import java.util.Map;

import jp.co.sample.kintai.shared.domain.DateRange;

/**
 * 読み込み済みの暦日区分を包んだ会社カレンダー。
 *
 * <p><strong>数え方を持たない。</strong> {@link CompanyCalendar#workdayCountIn} の既定実装を
 * そのまま使えるようにするためだけの入れ物である。
 *
 * <p>これが無いと、年度の所定労働日数を数えるのに
 * <strong>365 回の {@code SELECT}</strong> が飛ぶ（既定実装は日ごとに
 * {@link CompanyCalendar#dayTypeOf} を呼ぶ）。
 * かといって数え方をリポジトリ側に書くと、半開区間の扱いを実装の数だけ間違えられる
 * （CLAUDE.md 落とし穴 37・67）。
 *
 * @param registered 登録されている暦日区分。<strong>未登録の日はキーごと現れない</strong>
 */
public record RegisteredCalendar(Map<LocalDate, DayType> registered)
        implements CompanyCalendar {

    public RegisteredCalendar {
        if (registered == null) {
            throw new IllegalArgumentException("暦日区分に null は許されません");
        }
        registered = Map.copyOf(registered);
    }

    /**
     * 暦日の区分。<strong>未登録の日は所定労働日として扱う。</strong>
     *
     * <p>登録漏れを休日と判定すると通常勤務に休日割増が付いて過払いになる。
     * この既定は本番のリポジトリと同じである（そろえないと計算結果が変わる）。
     */
    @Override
    public DayType dayTypeOf(LocalDate date) {
        return registered.getOrDefault(date, DayType.WORKDAY);
    }

    /** 未登録の日。<strong>「登録されている」と「所定労働日である」は別の事実である。</strong> */
    public java.util.List<LocalDate> missingDates(DateRange period) {
        java.util.List<LocalDate> missing = new java.util.ArrayList<>();
        for (LocalDate date = period.from(); date.isBefore(period.toExclusive());
                date = date.plusDays(1)) {
            if (!registered.containsKey(date)) {
                missing.add(date);
            }
        }
        return java.util.List.copyOf(missing);
    }
}
