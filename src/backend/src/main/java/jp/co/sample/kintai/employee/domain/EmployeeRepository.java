package jp.co.sample.kintai.employee.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/** 社員のポート。実装は {@code infrastructure}。 */
public interface EmployeeRepository {

    Optional<Employee> findById(EmployeeId id);

    /** 社員番号は認証 ID を兼ねるので、ログインでも使う。 */
    Optional<Employee> findByNumber(EmployeeNumber number);

    /**
     * 名簿に載せる社員（API設計書 3.2）。
     *
     * <p><strong>「その日に在籍していたか」を訊く問いではない。</strong>
     * 名簿では<strong>未来日入社の社員も必ず返す。</strong>
     * 登録直後の社員が一覧に現れないと、管理者が登録の成否を確認できない。
     *
     * @param includeRetired 退職者を含めるか。<strong>絞るのはこれだけ</strong>
     */
    List<Employee> findForDirectory(LocalDate asOf, boolean includeRetired);

    /**
     * 在籍期間が指定期間と<strong>重なる</strong>社員（BR-18）。
     *
     * <p><strong>基準日 1 点で訊いてはならない。</strong>
     * 1 点だと月中入社か月中退職のどちらかが必ず落ちる。
     * 給与連携も一括締めも「その月に 1 日でも在籍した社員」を数え上げるので、除くと
     * <strong>退職者の最終月の給与が出ない</strong>（CLAUDE.md 落とし穴 63・75）。
     *
     * <p>社員番号順、同じ番号なら入社日順。
     * 社員番号は在籍者のあいだでしか一意でないので（部分一意インデックス）、
     * 番号だけでは順序が一意に決まらない月がある。
     */
    List<Employee> findEmployedDuring(DateRange period);

    /**
     * 識別子でまとめて読む。
     *
     * <p>1 件ずつ引くと社員数ぶんの問い合わせになる。
     * 給与連携（BR-18）が記録に残した対象社員を読み直すときに使う。
     */
    List<Employee> findByIds(Collection<EmployeeId> ids);

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
