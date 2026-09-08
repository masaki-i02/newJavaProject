package jp.co.sample.kintai.workrule.domain;

import java.time.LocalDate;
import java.util.Map;

import jp.co.sample.kintai.shared.domain.DateRange;

/**
 * 会社カレンダーのポート。
 *
 * <p>{@link CompanyCalendar} を継承するので、そのまま計算へ渡せる。
 * 日数の数え方は既定実装が持つ（実装ごとに書き直す理由が無い）。
 */
public interface CompanyCalendarRepository extends CompanyCalendar {

    /**
     * 期間分をまとめて取得する。
     *
     * <p>月次の集計で日ごとに問い合わせると N+1 になる。
     * <strong>未登録の日はキーごと現れない</strong>（既定は所定労働日）。
     */
    Map<LocalDate, DayType> findByPeriod(DateRange period);

    /**
     * 期間分の名称（祝日名など）。
     *
     * <p><strong>{@link #findByPeriod} と分ける。</strong>
     * 計算に要るのは暦日区分だけで、名称は表示のためにしか使わない。
     * 1 つにまとめると、月次の集計が使わない文字列を毎回読むことになる。
     *
     * <p>名称は<strong>書き込みだけ受け付けて読み出せない状態にしない。</strong>
     * `PUT /api/calendars/{date}` と一括登録は名称を受け取るので、
     * 返す経路が無いと、人事が登録した祝日名がどこにも出ない。
     *
     * <p>名称が無い日はキーごと現れない。
     */
    Map<LocalDate, String> findNamesByPeriod(DateRange period);

    void save(LocalDate date, DayType dayType, String name);
}
