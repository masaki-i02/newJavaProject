package jp.co.sample.kintai.attendance.domain.monthly;

import java.time.YearMonth;
import java.util.Optional;

import jp.co.sample.kintai.shared.domain.EmployeeId;

/** 月次清算のポート。実装は {@code infrastructure}。 */
public interface MonthlySettlementRepository {

    /**
     * 保存する。同じ社員・同じ対象月の行があれば置き換える（再計算）。
     *
     * <p>週ごとの内訳も<strong>まるごと入れ替える。</strong>
     * 部分更新にすると、再計算で週の数が減ったとき（月中退職など）に古い週が残り、
     * 内訳の合計が月次の時間外と食い違う。
     */
    void save(MonthlySettlement settlement);

    Optional<MonthlySettlement> find(EmployeeId employeeId, YearMonth month);

    /**
     * 当年度の、指定月より前の 36 協定対象時間の累計（BR-12）。
     *
     * <p>年度をまたぐと 0 から数え直す。暦年で数えると 1 月に上限がリセットされ、
     * <strong>年 360 時間の上限が実質 15 か月ぶんになる。</strong>
     */
    java.time.Duration annualSubjectTimeBefore(EmployeeId employeeId, YearMonth month);
}
