package jp.co.sample.kintai.workrule.infrastructure;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 就業規則の版の永続化。パッケージプライベート（AR-05）。
 *
 * <p>固定時間制とフレックスで使う列が違う。<strong>片方は必ず NULL になる。</strong>
 * どちらの列が埋まっているべきかは DB の {@code work_rules_variant_check} が守るので、
 * ここでは素直に nullable にしておく。
 *
 * <p>{@code scheduled_working_minutes} は生成列なのでマッピングしない。
 * 書き込むとドメインの計算と DB の計算が食い違いうる。
 */
@Entity
@Table(name = "work_rules")
class WorkRuleEntity {

    @Id
    private UUID id;

    @Column(name = "series_id", nullable = false)
    private UUID seriesId;

    @Column(name = "working_time_system", nullable = false)
    private String workingTimeSystem;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "scheduled_start")
    private LocalTime scheduledStart;

    @Column(name = "scheduled_end")
    private LocalTime scheduledEnd;

    @Column(name = "scheduled_break_minutes")
    private Integer scheduledBreakMinutes;

    @Column(name = "flexible_start")
    private LocalTime flexibleStart;

    @Column(name = "flexible_end")
    private LocalTime flexibleEnd;

    @Column(name = "core_start")
    private LocalTime coreStart;

    @Column(name = "core_end")
    private LocalTime coreEnd;

    @Column(name = "standard_daily_minutes")
    private Integer standardDailyMinutes;

    @Column(name = "statutory_daily_minutes", nullable = false)
    private int statutoryDailyMinutes;

    @Column(name = "statutory_weekly_minutes", nullable = false)
    private int statutoryWeeklyMinutes;

    @Column(name = "night_start", nullable = false)
    private LocalTime nightStart;

    @Column(name = "night_end", nullable = false)
    private LocalTime nightEnd;

    @Column(name = "rate_overtime", nullable = false)
    private BigDecimal rateOvertime;

    @Column(name = "rate_night", nullable = false)
    private BigDecimal rateNight;

    @Column(name = "rate_legal_holiday", nullable = false)
    private BigDecimal rateLegalHoliday;

    @Version
    private long version;

    protected WorkRuleEntity() {
    }

    WorkRuleEntity(UUID id) {
        this.id = id;
    }

    UUID getId() {
        return id;
    }

    UUID getSeriesId() {
        return seriesId;
    }

    void setSeriesId(UUID seriesId) {
        this.seriesId = seriesId;
    }

    String getWorkingTimeSystem() {
        return workingTimeSystem;
    }

    void setWorkingTimeSystem(String workingTimeSystem) {
        this.workingTimeSystem = workingTimeSystem;
    }

    LocalDate getValidFrom() {
        return validFrom;
    }

    void setValidFrom(LocalDate validFrom) {
        this.validFrom = validFrom;
    }

    LocalDate getValidTo() {
        return validTo;
    }

    void setValidTo(LocalDate validTo) {
        this.validTo = validTo;
    }

    LocalTime getScheduledStart() {
        return scheduledStart;
    }

    void setScheduledStart(LocalTime scheduledStart) {
        this.scheduledStart = scheduledStart;
    }

    LocalTime getScheduledEnd() {
        return scheduledEnd;
    }

    void setScheduledEnd(LocalTime scheduledEnd) {
        this.scheduledEnd = scheduledEnd;
    }

    Integer getScheduledBreakMinutes() {
        return scheduledBreakMinutes;
    }

    void setScheduledBreakMinutes(Integer scheduledBreakMinutes) {
        this.scheduledBreakMinutes = scheduledBreakMinutes;
    }

    LocalTime getFlexibleStart() {
        return flexibleStart;
    }

    void setFlexibleStart(LocalTime flexibleStart) {
        this.flexibleStart = flexibleStart;
    }

    LocalTime getFlexibleEnd() {
        return flexibleEnd;
    }

    void setFlexibleEnd(LocalTime flexibleEnd) {
        this.flexibleEnd = flexibleEnd;
    }

    LocalTime getCoreStart() {
        return coreStart;
    }

    void setCoreStart(LocalTime coreStart) {
        this.coreStart = coreStart;
    }

    LocalTime getCoreEnd() {
        return coreEnd;
    }

    void setCoreEnd(LocalTime coreEnd) {
        this.coreEnd = coreEnd;
    }

    Integer getStandardDailyMinutes() {
        return standardDailyMinutes;
    }

    void setStandardDailyMinutes(Integer standardDailyMinutes) {
        this.standardDailyMinutes = standardDailyMinutes;
    }

    int getStatutoryDailyMinutes() {
        return statutoryDailyMinutes;
    }

    void setStatutoryDailyMinutes(int statutoryDailyMinutes) {
        this.statutoryDailyMinutes = statutoryDailyMinutes;
    }

    int getStatutoryWeeklyMinutes() {
        return statutoryWeeklyMinutes;
    }

    void setStatutoryWeeklyMinutes(int statutoryWeeklyMinutes) {
        this.statutoryWeeklyMinutes = statutoryWeeklyMinutes;
    }

    LocalTime getNightStart() {
        return nightStart;
    }

    void setNightStart(LocalTime nightStart) {
        this.nightStart = nightStart;
    }

    LocalTime getNightEnd() {
        return nightEnd;
    }

    void setNightEnd(LocalTime nightEnd) {
        this.nightEnd = nightEnd;
    }

    BigDecimal getRateOvertime() {
        return rateOvertime;
    }

    void setRateOvertime(BigDecimal rateOvertime) {
        this.rateOvertime = rateOvertime;
    }

    BigDecimal getRateNight() {
        return rateNight;
    }

    void setRateNight(BigDecimal rateNight) {
        this.rateNight = rateNight;
    }

    BigDecimal getRateLegalHoliday() {
        return rateLegalHoliday;
    }

    void setRateLegalHoliday(BigDecimal rateLegalHoliday) {
        this.rateLegalHoliday = rateLegalHoliday;
    }
}
