package jp.co.sample.kintai.workrule.infrastructure;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 就業規則の系列 の Spring Data リポジトリ。
 *
 * <p><strong>トップレベルに置く。</strong>
 * Spring Data はクラスの内側に入れ子にしたインタフェースを走査しないので、
 * まとめて 1 ファイルに書くと Bean が作られない。
 */
interface WorkRuleSeriesJpaRepository extends JpaRepository<WorkRuleSeriesEntity, UUID> {

    List<WorkRuleSeriesEntity> findAllByOrderByName();

    /**
     * 版を 1 つ進める。
     *
     * <p><strong>条件を SQL に含める。</strong>
     * 読んでから比べて書くと、その隙間に別の改定が入る。
     * 更新できた行数が 0 なら、期待した版はもう古い。
     *
     * <p>{@code @Version} の自動更新には任せない。
     * 系列の行そのものは改定で変わらないので、
     * Hibernate から見ると<strong>汚れておらず、版が進まない</strong>。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update WorkRuleSeriesEntity s set s.version = s.version + 1"
            + " where s.id = :id and s.version = :expectedVersion")
    int bumpVersion(@Param("id") UUID id, @Param("expectedVersion") long expectedVersion);
}
