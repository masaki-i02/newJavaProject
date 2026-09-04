package jp.co.sample.kintai.employee.domain;

/**
 * パスワードのハッシュ化と照合のポート。
 *
 * <p>実装は {@code infrastructure}（BCrypt）。
 * <strong>ドメインはアルゴリズムを知らない。</strong>
 * 知っているのは「ハッシュにできること」と「照合できること」だけである。
 */
public interface PasswordHasher {

    PasswordHash hash(RawPassword password);

    /**
     * 照合する。
     *
     * <p><strong>一致しない場合も同じ時間をかける実装であること。</strong>
     * 早期に返す実装だと、応答時間の差から情報が漏れる。
     */
    boolean matches(PasswordAttempt attempt, PasswordHash hash);

    /**
     * 存在しない社員番号に対して、照合と同じだけの時間を使う。
     *
     * <p><strong>社員番号の存在を応答時間から悟らせないため。</strong>
     * 社員が見つからないときに照合を飛ばすと、BCrypt の計算時間ぶんだけ
     * 応答が速くなり、総当たりで在籍者の一覧を作れる。
     */
    void wasteTime();
}
