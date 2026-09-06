package jp.co.sample.kintai.leave.application;

import java.time.Clock;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jp.co.sample.kintai.shared.domain.BusinessZone;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.Requester;
import jp.co.sample.kintai.shared.domain.Role;

/**
 * 年次有給休暇の付与を毎日実行する（BR-14）。
 *
 * <p><strong>ユースケースをそのまま呼ぶ。</strong> 手順をここに書かない（落とし穴 67）。
 * 人事の API と同じ {@link PaidLeaveGrantService#grantAsOf} を通る。
 *
 * <p><strong>依頼者を引数で渡す。</strong>
 * {@code SecurityContextHolder} を読む設計にすると、
 * 認証の無いバッチからユースケースを呼べなくなる（落とし穴 42）。
 * バッチはシステムの行為なので、人事として実行する。
 *
 * <p>到来済みで未処理の付与をすべて作るので、
 * <strong>実行し損ねた日があっても次の実行で追いつく</strong>（落とし穴 26）。
 */
@Component
@ConditionalOnProperty(name = "kintai.paid-leave.grant-scheduler.enabled",
        havingValue = "true", matchIfMissing = true)
public class PaidLeaveGrantScheduler {

    private static final Logger log = LoggerFactory.getLogger(PaidLeaveGrantScheduler.class);

    /** バッチの実行者。個人ではないので、識別子は固定の 0 とする。 */
    private static final Requester SYSTEM = new Requester(
            new EmployeeId(new java.util.UUID(0L, 0L)),
            java.util.Set.of(Role.EMPLOYEE, Role.HR));

    private final PaidLeaveGrantService grants;
    private final Clock clock;

    public PaidLeaveGrantScheduler(PaidLeaveGrantService grants, Clock clock) {
        this.grants = grants;
        this.clock = clock;
    }

    @Scheduled(cron = "${kintai.paid-leave.grant-scheduler.cron:0 30 2 * * *}",
            zone = BusinessZone.NAME)
    public void grantToday() {
        LocalDate today = LocalDate.now(clock);
        GrantResult result = grants.grantAsOf(SYSTEM, today);
        log.info("年次有給休暇の付与を実行しました: 基準日 {} / 付与 {} 件 / 不付与 {} 件 / 済 {} 件",
                today, result.granted().size(), result.withheld().size(),
                result.skipped().size());
    }
}
