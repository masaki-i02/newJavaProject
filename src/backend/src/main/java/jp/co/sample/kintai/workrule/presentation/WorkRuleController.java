package jp.co.sample.kintai.workrule.presentation;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jp.co.sample.kintai.shared.domain.TimeOfDayRange;
import jp.co.sample.kintai.shared.presentation.AuthenticatedEmployee;
import jp.co.sample.kintai.workrule.application.WorkRuleMasterService;
import jp.co.sample.kintai.workrule.application.WorkRuleMasterService.RegisteredWorkRule;
import jp.co.sample.kintai.workrule.domain.WorkRule;
import jp.co.sample.kintai.workrule.application.WorkRuleMasterService.WorkRuleSpec;
import jp.co.sample.kintai.workrule.application.WorkRuleQueryService;
import jp.co.sample.kintai.workrule.domain.FixedTimeSystem;
import jp.co.sample.kintai.workrule.domain.FlextimeSystem;
import jp.co.sample.kintai.workrule.domain.NightWindow;
import jp.co.sample.kintai.workrule.domain.PremiumRates;
import jp.co.sample.kintai.workrule.domain.ScheduleCapacityWarning;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesId;
import jp.co.sample.kintai.workrule.domain.WorkingTimeSystem;

/**
 * 就業規則の登録・改定・参照（API 設計書 2）。
 *
 * <p><strong>パスが指すのは系列である。</strong>
 * 版の識別子は履歴の中にだけ現れる。社員に適用するのも系列であり、
 * 版を適用すると改定した瞬間に全社員の規則が「未設定」になる（落とし穴 13）。
 */
@RestController
@RequestMapping("/api/work-rules")
class WorkRuleController {

    private final WorkRuleMasterService master;
    private final WorkRuleQueryService queries;

    WorkRuleController(WorkRuleMasterService master, WorkRuleQueryService queries) {
        this.master = master;
        this.queries = queries;
    }

    /** 系列の一覧。版の履歴は含めない（一覧で全系列の全版を返すと重い）。 */
    @GetMapping
    List<WorkRuleResponse> list(@AuthenticationPrincipal AuthenticatedEmployee principal) {
        return queries.listSeries(principal.toRequester()).stream()
                .map(WorkRuleResponse::summaryOf).toList();
    }

    /** 系列の詳細と版の履歴。 */
    @GetMapping("/{seriesId}")
    WorkRuleResponse detail(@AuthenticationPrincipal AuthenticatedEmployee principal,
                            @PathVariable UUID seriesId) {
        var detail = queries.findSeriesDetail(principal.toRequester(), new WorkRuleSeriesId(seriesId));
        return WorkRuleResponse.of(detail.series(), detail.revisions());
    }

    /**
     * 指定日に有効な版。
     *
     * <p>版が無いのは<strong>正常に起こりうる</strong>（年度途中に新設した系列）。
     * 404 で返し、呼び出し側に「まだ効いていない」ことを伝える。
     */
    @GetMapping("/{seriesId}/effective")
    WorkRuleResponse.Revision effective(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @PathVariable UUID seriesId, @RequestParam LocalDate date) {
        return WorkRuleResponse.Revision.of(queries.findVersionEffectiveOn(
                principal.toRequester(), new WorkRuleSeriesId(seriesId), date));
    }

    /** 就業規則の新規登録（系列 + 初版）。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    RegistrationResponse register(@AuthenticationPrincipal AuthenticatedEmployee principal,
                                  @Valid @RequestBody RegistrationBody body) {
        return RegistrationResponse.of(
                master.registerWorkRule(principal.toRequester(), body.name(), body.toSpec()));
    }

    /**
     * 就業規則の改定（版を 1 つ足す）。
     *
     * <p><strong>版を必ず送る。</strong> 2 人の人事が同時に開いていたときの
     * 上書きを防ぐ。一致しなければ 409。
     */
    @PostMapping("/{seriesId}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    RegistrationResponse revise(@AuthenticationPrincipal AuthenticatedEmployee principal,
                                @PathVariable UUID seriesId,
                                @Valid @RequestBody RevisionBody body) {
        return RegistrationResponse.of(master.reviseWorkRule(principal.toRequester(),
                new WorkRuleSeriesId(seriesId), body.version(), body.toSpec()));
    }

    /**
     * 登録の入力。
     *
     * <p><strong>検証をここで行う。</strong>
     * ドメインの compact constructor に任せると、0 や負の値が
     * {@code IllegalArgumentException} になり<strong>理由の載らない 500</strong>
     * が返る（落とし穴 105）。法定の範囲を外れる指定は
     * ドメインが業務エラー（422）として返す。
     */
    record RegistrationBody(@NotBlank String name,
                            @NotNull LocalDate validFrom,
                            @Valid @NotNull SystemBody system,
                            NightWindow nightWindow,
                            @Valid PremiumRatesBody premiumRates) {

        WorkRuleSpec toSpec() {
            return SystemBody.spec(validFrom, system, nightWindow, premiumRates);
        }
    }

    /** 改定の入力。系列は経路が指すので本文に持たせない（落とし穴 64）。 */
    record RevisionBody(@NotNull Long version,
                        @NotNull LocalDate validFrom,
                        @Valid @NotNull SystemBody system,
                        NightWindow nightWindow,
                        @Valid PremiumRatesBody premiumRates) {

        WorkRuleSpec toSpec() {
            return SystemBody.spec(validFrom, system, nightWindow, premiumRates);
        }
    }

    /**
     * 労働時間制度の入力。
     *
     * <p><strong>どちらか一方だけを持つ。</strong>
     * 両方を送られたら拒否する。平坦に受けると
     * 「FLEX なのに始業時刻がある」形を作れてしまい、
     * DB の CHECK 制約が禁じた状態を API が再現する。
     */
    record SystemBody(@Valid FixedTimeBody fixedTime, @Valid FlextimeBody flextime) {

        WorkingTimeSystem toDomain() {
            if ((fixedTime == null) == (flextime == null)) {
                throw new InvalidWorkRuleRequestException(
                        "fixedTime と flextime はどちらか一方だけを指定します");
            }
            return fixedTime != null ? fixedTime.toDomain() : flextime.toDomain();
        }

        /**
         * <strong>法定労働時間は入力として受け取らない。</strong>
         *
         * <p>1 日 8 時間・1 週 40 時間は<strong>法が決める定数</strong>であり
         * （労基法 32 条）、会社が設定するものではない。
         * 会社が定めるのは<strong>所定</strong>労働時間のほうで、
         * それは {@code system} が持っている。
         *
         * <p>受け取ると、40 時間未満を指定した規則を作れてしまう。
         * ドメインは「40 時間以下」しか課さないので通り、
         * ところが {@code monthly_settlements_statutory_limit_check} と
         * {@code weekly_overtimes_calculation_check} は式に 2400 を直書きしている。
         * <strong>その社員の月次清算は永久に保存できず、理由の載らない 500 になる</strong>
         * （IT-API-46）。<strong>検査されていない入力は、
         * 設定できるという見かけだけを増やす</strong>（落とし穴 126）。
         *
         * <p>ドメインの {@code WorkRule} は引数として受け取り続ける。
         * 計算が規則の値を読んでいるのか同じ定数を偶然使っているのかを、
         * テストが区別できなくなるからである（落とし穴 55）。
         */
        static WorkRuleSpec spec(LocalDate validFrom, SystemBody system,
                                 NightWindow nightWindow, PremiumRatesBody rates) {
            return new WorkRuleSpec(validFrom, system.toDomain(),
                    WorkRule.STATUTORY_DAILY, WorkRule.STATUTORY_WEEKLY,
                    nightWindow == null ? NightWindow.STANDARD : nightWindow,
                    rates == null ? PremiumRates.STATUTORY : rates.toDomain());
        }
    }

    /** 固定時間制。所定は始業・終業・休憩から導く（同じ値を 2 か所に持たせない）。 */
    record FixedTimeBody(@NotNull LocalTime scheduledStart, @NotNull LocalTime scheduledEnd,
                         @NotNull Long scheduledBreakMinutes) {

        FixedTimeSystem toDomain() {
            // ★ 条件をここに書き写さない。ドメインが持つ規則（休憩が拘束時間を超えない・
            //   所定が 0 を超える）を 2 か所に持つと、片方だけが古くなる（落とし穴 67）。
            //   ここでやるのは「実装の不備」から「利用者が直せる誤り」への写しだけである。
            //   包まないと、休憩 600 分のような画面から送れる値が理由の載らない 500 になる
            return rejectAsInvalidRequest(() -> new FixedTimeSystem(
                    scheduledStart, scheduledEnd, Duration.ofMinutes(scheduledBreakMinutes)));
        }
    }

    /** フレックスタイム制。 */
    record FlextimeBody(@NotNull LocalTime flexibleStart, @NotNull LocalTime flexibleEnd,
                        @NotNull LocalTime coreStart, @NotNull LocalTime coreEnd,
                        @NotNull @Positive Long standardDailyMinutes) {

        FlextimeSystem toDomain() {
            // ★ 同上。コア時刻の開始と終了を同じ値にすると TimeOfDayRange が拒むが、
            //   画面のプルダウンは同じ値を選べる（落とし穴 105）
            return rejectAsInvalidRequest(() -> new FlextimeSystem(
                    new TimeOfDayRange(flexibleStart, flexibleEnd),
                    new TimeOfDayRange(coreStart, coreEnd),
                    Duration.ofMinutes(standardDailyMinutes)));
        }
    }

    /** 割増率。文字列で受ける（浮動小数点を経由させない）。 */
    record PremiumRatesBody(@NotBlank String overtimeBeyondStatutory,
                            @NotBlank String night, @NotBlank String legalHoliday) {

        PremiumRates toDomain() {
            // ★ 数値として読めるかはここで見るが、桁数と法定下限はドメインが持つ。
            //   包まないと "0.2555" のような値が理由の載らない 500 になる
            return rejectAsInvalidRequest(
                    () -> new PremiumRates(decimal(overtimeBeyondStatutory, "時間外"),
                            decimal(night, "深夜"), decimal(legalHoliday, "法定休日")));
        }

        private static BigDecimal decimal(String value, String label) {
            try {
                return new BigDecimal(value);
            } catch (NumberFormatException e) {
                throw new InvalidWorkRuleRequestException(
                        "%sの割増率が数値として読めません: %s".formatted(label, value));
            }
        }
    }

    /** 登録・改定の応答。空の {@code warnings} は項目ごと省く（落とし穴 76）。 */
    record RegistrationResponse(String seriesId, String workRuleId, long version,
                                @com.fasterxml.jackson.annotation.JsonInclude(
                                        com.fasterxml.jackson.annotation.JsonInclude
                                                .Include.NON_EMPTY)
                                List<Warning> warnings) {

        static RegistrationResponse of(RegisteredWorkRule registered) {
            return new RegistrationResponse(registered.seriesId().value().toString(),
                    registered.workRuleId().value().toString(), registered.version(),
                    registered.warnings().stream().map(Warning::of).toList());
        }
    }

    /**
     * 所定総労働時間が法定の総枠を超える月。
     *
     * <p><strong>拒否ではなく知らせである。</strong>
     * フレックスでは適法に起こりうるので、拒むと実際に運用している会社が登録できない。
     */
    record Warning(String code, String message, String month,
                   long scheduledTotalMinutes, long statutoryTotalLimitMinutes) {

        static Warning of(ScheduleCapacityWarning warning) {
            return new Warning("schedule-exceeds-statutory-limit",
                    "%s は所定総労働時間 %d 分が法定労働時間の総枠 %d 分を超えます"
                            .formatted(warning.month(), warning.scheduled().toMinutes(),
                                    warning.limit().toMinutes()),
                    warning.month().toString(),
                    warning.scheduled().toMinutes(), warning.limit().toMinutes());
        }
    }

    /**
     * ドメインが投げる {@code IllegalArgumentException} を 422 へ写す。
     *
     * <p><strong>条件を presentation に書き写さない。</strong>
     * 規則の定義を持つのはドメインであり、写すと片方だけが古くなる（落とし穴 67）。
     * ここでやるのは型の付け替えだけで、<strong>利用者が送った値が悪いことを、
     * 利用者が直せる形で返す</strong>ためにある（落とし穴 105）。
     *
     * <p>null は {@code @NotNull} が先に 400 で弾くので、ここへ来るのは
     * 「形式は整っているが業務上ありえない組み合わせ」だけである。
     */
    private static <T> T rejectAsInvalidRequest(java.util.function.Supplier<T> construct) {
        try {
            return construct.get();
        } catch (IllegalArgumentException e) {
            throw new InvalidWorkRuleRequestException(e.getMessage());
        }
    }

    /** 就業規則の入力が不正。実装の不備ではないので 422 で返す。 */
    static final class InvalidWorkRuleRequestException
            extends jp.co.sample.kintai.shared.domain.DomainException {

        @java.io.Serial
        private static final long serialVersionUID = 1L;

        InvalidWorkRuleRequestException(String message) {
            super(message);
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:invalid-work-rule-request";
        }

        @Override
        public jp.co.sample.kintai.shared.domain.DomainErrorKind kind() {
            return jp.co.sample.kintai.shared.domain.DomainErrorKind.RULE_VIOLATION;
        }

        @Override
        public String title() {
            return "就業規則の指定が不正です";
        }
    }
}
