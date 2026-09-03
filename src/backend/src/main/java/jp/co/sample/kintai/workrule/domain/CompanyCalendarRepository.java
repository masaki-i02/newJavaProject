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

    void save(LocalDate date, DayType dayType, String name);
}
