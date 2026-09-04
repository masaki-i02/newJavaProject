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

    /**
     * 版が一致するときだけ保存する。
     *
     * <p><strong>人事が画面から再計算するときに使う。</strong>
     * 画面に出ている値を見て「これを計算し直す」と決めた以上、
     * その間に別の経路（訂正の承認など）で値が変わっていたら、
     * 人事が見ていない結果を上書きすることになる。
     *
     * <p>システムが契機（提出・訂正の承認）で再計算するときは
     * {@link #save(MonthlySettlement)} を使う。
     * 誰かが見ている値ではないので、版を突き合わせる相手がいない。
     *
     * @throws org.springframework.dao.OptimisticLockingFailureException 版が一致しない場合
     */
    void save(MonthlySettlement settlement, long expectedVersion);

    /** 現在の版。まだ計算されていなければ 0。 */
    long currentVersion(EmployeeId employeeId, YearMonth month);

    Optional<MonthlySettlement> find(EmployeeId employeeId, YearMonth month);

    /**
     * 当年度の、指定月より前の 36 協定対象時間の累計（BR-12）。
     *
     * <p>年度をまたぐと 0 から数え直す。暦年で数えると 1 月に上限がリセットされ、
     * <strong>年 360 時間の上限が実質 15 か月ぶんになる。</strong>
     */
    java.time.Duration annualSubjectTimeBefore(EmployeeId employeeId, YearMonth month);
}
