package jp.co.sample.kintai.workrule.application;

import java.io.Serial;
import java.time.LocalDate;
import java.time.YearMonth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sample.kintai.shared.application.AccessDeniedException;
import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.MonthClosureQuery;
import jp.co.sample.kintai.shared.domain.Requester;
import jp.co.sample.kintai.shared.domain.Role;
import jp.co.sample.kintai.workrule.domain.CompanyCalendarRepository;
import jp.co.sample.kintai.workrule.domain.DayType;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesId;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesRepository;

/**
 * カレンダーと就業規則の適用を変更する（人事）。
 *
 * <p><strong>締め済みの月に影響する変更を拒む。</strong>
 * 暦日区分が変わると休日割増の計算が変わり、就業規則が変わると所定が変わる。
 * どちらも<strong>確定済みの勤怠と矛盾する。</strong>
 * 締めた月を戻す手段は用意していないので、矛盾したまま残る。
 *
 * <p>締め状態は {@code shared.domain} の {@link MonthClosureQuery} 越しに問う。
 * {@code approval} の型を直接見ると、依存図に無い
 * <strong>{@code workrule → approval} の辺</strong>が生まれる（ADR 0004）。
 */
@Service
public class WorkRuleMasterService {

    private final CompanyCalendarRepository calendar;
    private final WorkRuleSeriesRepository series;
    private final MonthClosureQuery monthClosure;

    public WorkRuleMasterService(CompanyCalendarRepository calendar,
                                 WorkRuleSeriesRepository series,
                                 MonthClosureQuery monthClosure) {
        this.calendar = calendar;
        this.series = series;
        this.monthClosure = monthClosure;
    }

    /**
     * 暦日区分を設定する。
     *
     * <p><strong>判定は「誰か 1 人でも締めたか」で行う。</strong>
     * カレンダーは全社で共有する 1 つの表なので、
     * 特定の社員が未締めでも、他の社員が締めていれば変更してはいけない。
     */
    @Transactional
    public void 暦日区分を設定する(Requester requester, LocalDate date, DayType dayType,
                            String name) {
        requireHumanResources(requester);
        YearMonth month = YearMonth.from(date);
        if (monthClosure.isClosedForAnyone(month)) {
            throw new MonthAlreadyClosedException(month, "会社カレンダー");
        }
        calendar.save(date, dayType, name);
    }

    /**
     * 社員に就業規則を適用する。
     *
     * <p><strong>過去へ遡って適用できない</strong>（適用開始日が締め済みの月に入る場合）。
     * 遡らせると、確定済みの月の所定労働時間が後から変わる。
     */
    @Transactional
    public void 就業規則を適用する(Requester requester, EmployeeId employeeId,
                            WorkRuleSeriesId seriesId, LocalDate validFrom) {
        requireHumanResources(requester);
        YearMonth month = YearMonth.from(validFrom);
        if (monthClosure.isClosed(employeeId, month)) {
            throw new MonthAlreadyClosedException(month, "就業規則の適用");
        }
        series.assign(employeeId, seriesId, validFrom);
    }

    private static void requireHumanResources(Requester requester) {
        if (!requester.has(Role.HR)) {
            throw new AccessDeniedException();
        }
    }

    /**
     * 締め済みの月に影響する変更。
     *
     * <p><strong>状態が変われば通るので {@code CONFLICT}（409）。</strong>
     * ただし締めを戻す手段は用意していないので、実際には通らない。
     */
    public static final class MonthAlreadyClosedException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        MonthAlreadyClosedException(YearMonth month, String 対象) {
            super("締め済みの月に影響するため%sを変更できません: %s".formatted(対象, month));
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:month-already-closed";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.CONFLICT;
        }

        @Override
        public String title() {
            return "締め済みの月に影響する変更はできません";
        }
    }
}
