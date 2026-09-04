package jp.co.sample.kintai.employee.infrastructure;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import jp.co.sample.kintai.employee.domain.PasswordAttempt;
import jp.co.sample.kintai.employee.domain.PasswordHash;
import jp.co.sample.kintai.employee.domain.PasswordHasher;
import jp.co.sample.kintai.employee.domain.RawPassword;

/**
 * {@link PasswordHasher} の実装。
 *
 * <p>アルゴリズムを知っているのはこのクラスだけである。
 * ドメインは「その形をしていること」しか知らない。
 */
@Component
class BCryptPasswordHasher implements PasswordHasher {

    /**
     * 存在しない社員番号に対して照合の代わりに使うダミーのハッシュ。
     *
     * <p><strong>実在しない値でよい。</strong> 一致することは無く、
     * 目的は「BCrypt の計算時間を消費すること」だけである。
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final PasswordEncoder encoder;

    BCryptPasswordHasher(PasswordEncoder encoder) {
        this.encoder = encoder;
    }

    @Override
    public PasswordHash hash(RawPassword password) {
        return new PasswordHash(encoder.encode(password.value()));
    }

    @Override
    public boolean matches(PasswordAttempt attempt, PasswordHash hash) {
        return encoder.matches(attempt.value(), hash.value());
    }

    /**
     * 照合と同じだけ時間を使う。
     *
     * <p><strong>結果は捨てる。</strong> 捨てるが、呼ばないと
     * 社員番号の存在が応答時間の差から分かる。
     * 最適化で消えないよう、戻り値を使う形にしてある。
     */
    @Override
    public void wasteTime() {
        if (encoder.matches("dummy-password-for-timing", DUMMY_HASH)) {
            throw new IllegalStateException("ダミーのハッシュに一致することはありえません");
        }
    }
}
