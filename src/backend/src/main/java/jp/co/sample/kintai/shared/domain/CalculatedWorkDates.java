package jp.co.sample.kintai.shared.domain;

import java.time.LocalDate;
import java.util.Set;

/**
 * 日次勤怠が確定している勤務日を問い合わせるポート。
 *
 * <p>日次勤怠を持つのは {@code attendance} だが、それを知りたいのは
 * <strong>提出の事前条件を確かめる {@code approval}</strong> である。
 * 素直に問い合わせると依存が循環するので、
 * ポートを {@code shared} に置き、実装を {@code attendance} に置く（ADR 0004）。
 *
 * <p><strong>「打刻があるか」ではなく「計算が確定しているか」を返す。</strong>
 * 欠勤の日は打刻が無いまま確定するので、打刻の有無で数えると
 * 欠勤のある月を永久に提出できなくなる。
 */
public interface CalculatedWorkDates {

    Set<LocalDate> of(EmployeeId employeeId, DateRange period);
}
