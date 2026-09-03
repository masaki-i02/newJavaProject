package jp.co.sample.kintai.workrule.infrastructure;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 就業規則の版 の Spring Data リポジトリ。
 *
 * <p><strong>トップレベルに置く。</strong>
 * Spring Data はクラスの内側に入れ子にしたインタフェースを走査しないので、
 * まとめて 1 ファイルに書くと Bean が作られない。
 */
interface WorkRuleJpaRepository extends JpaRepository<WorkRuleEntity, UUID> {

    List<WorkRuleEntity> findBySeriesIdOrderByValidFrom(UUID seriesId);

    /** 複数の系列の版をまとめて引く。月次で系列ごとに引くと N+1 になる。 */
    List<WorkRuleEntity> findBySeriesIdInOrderByValidFrom(List<UUID> seriesIds);
}
