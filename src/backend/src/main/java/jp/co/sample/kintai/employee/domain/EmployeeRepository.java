package jp.co.sample.kintai.employee.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jp.co.sample.kintai.shared.domain.EmployeeId;

/** 社員のポート。実装は {@code infrastructure}。 */
public interface EmployeeRepository {

    Optional<Employee> findById(EmployeeId id);

    /** 社員番号は認証 ID を兼ねるので、ログインでも使う。 */
    Optional<Employee> findByNumber(EmployeeNumber number);

    /**
     * 指定日時点の社員一覧。
     *
     * @param includeRetired 退職者を含めるか。過去分の勤怠を扱うときは含める必要がある
     */
    List<Employee> findAll(LocalDate asOf, boolean includeRetired);

    /**
     * 名簿に載せる社員（API設計書 3.2）。
     *
     * <p>{@link #findAll(LocalDate, boolean)} とは<strong>別の問い</strong>である。
     * あちらは「その日に在籍していたか」を訊いており、未来日入社の社員は含まれない。
     * 名簿では<strong>未来日入社の社員も必ず返す。</strong>
     * 登録直後の社員が一覧に現れないと、管理者が登録の成否を確認できない。
     *
     * @param includeRetired 退職者を含めるか。<strong>絞るのはこれだけ</strong>
     */
    List<Employee> findForDirectory(LocalDate asOf, boolean includeRetired);

    void save(Employee employee);

    /**
     * 版が一致するときだけ保存する（API設計書 1.4）。
     *
     * <p><strong>人事が画面を見て決めた変更に使う。</strong>
     * 画面に出ている値を見て「これを直す」と決めた以上、
     * その間に別の経路（退職の登録など）で変わっていたら、
     * 見ていない結果を上書きすることになる。
     *
     * @throws org.springframework.dao.OptimisticLockingFailureException 版が一致しない場合
     */
    void save(Employee employee, long expectedVersion);

    /** 現在の版。 */
    long currentVersion(EmployeeId id);

    /**
     * 社員番号が在籍者と重複するか。
     *
     * <p>DB の部分一意インデックスでも守られるが、
     * <strong>一意制約違反は利用者に説明できない。</strong>
     * どの項目が重複しているかを返せるよう、登録の時点で確かめる。
     */
    boolean existsActiveNumber(EmployeeNumber number);

    /** メールアドレスが在籍者と重複するか。<strong>大文字小文字を区別しない。</strong> */
    boolean existsActiveEmail(Email email);
}
