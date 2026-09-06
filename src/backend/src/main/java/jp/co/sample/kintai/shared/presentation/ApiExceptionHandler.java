package jp.co.sample.kintai.shared.presentation;

import java.net.URI;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jp.co.sample.kintai.shared.domain.DetailedDomainException;
import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;

/**
 * 例外を RFC 9457 (Problem Details) へ変換する（アーキテクチャ設計書 6.2）。
 *
 * <p><strong>業務エラーと実装の不備を分ける。</strong>
 * 前者は {@link DomainException} を継承しており、利用者に見せて直してもらう。
 * 後者（{@code null}・桁あふれ・ありえない状態）は利用者に見せる意味が無いので、
 * <strong>詳細を応答に載せず</strong>ログへ出して 500 を返す。
 * 例外のメッセージをそのまま返すと、内部の構造や SQL の制約名が漏れる。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * 業務エラー。
     *
     * <p>ステータスは {@link DomainErrorKind} から決める。
     * <strong>{@code default} 句を書かない</strong>ので、種別を足した瞬間に
     * ここがコンパイルエラーになる。対応表を文字列で引く形にすると、
     * 追加した例外が 500 で黙って漏れる。
     */
    @ExceptionHandler(DomainException.class)
    ProblemDetail handleDomain(DomainException e) {
        HttpStatus status = switch (e.kind()) {
            case RULE_VIOLATION -> HttpStatus.UNPROCESSABLE_CONTENT;
            case CONFLICT -> HttpStatus.CONFLICT;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setType(URI.create(e.errorCode()));
        problem.setTitle(e.title());
        // ★ 「どれを直せばよいか」を持っている例外は、その情報も応答へ載せる。
        //   例外の側が知っているのに応答へ出さないと、利用者は総当たりで探すことになる
        if (e instanceof DetailedDomainException detailed) {
            detailed.properties().forEach(problem::setProperty);
        }
        return problem;
    }

    /** 入力形式の不正。<strong>どの項目を直せばよいか</strong>を項目ごとに返す。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "入力内容を確認してください");
        problem.setType(URI.create("urn:kintai:error:validation-failed"));
        problem.setTitle("入力形式が不正です");
        List<FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        problem.setProperty("errors", errors);
        return problem;
    }

    /**
     * 必須のクエリパラメータが無い。
     *
     * <p><strong>Spring の既定に任せない。</strong>
     * 既定では応答本文を持たないまま {@code /error} へ内部転送され、
     * その転送を Spring Security の {@code anyRequest().denyAll()} が拒むので、
     * <strong>利用者には本文の無い 403 として届く。</strong>
     * 「権限が無い」と「要求が足りない」を取り違える。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    ProblemDetail handleMissingParameter(MissingServletRequestParameterException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "必須のパラメータがありません: " + e.getParameterName());
        problem.setType(URI.create("urn:kintai:error:validation-failed"));
        problem.setTitle("入力形式が不正です");
        problem.setProperty("errors",
                List.of(new FieldError(e.getParameterName(), "必須です")));
        return problem;
    }

    /**
     * パラメータの型が合わない（{@code month=xxxx}・{@code {id}} が UUID でない）。
     *
     * <p><strong>実装の不備として 500 にしない。</strong>
     * {@code UUID.fromString} も {@code LocalDate.parse} も
     * {@code IllegalArgumentException} 系を投げるので、
     * 下の {@code handleImplementationDefect} が拾って
     * <strong>理由の載らない 500</strong> になっていた（CLAUDE.md 落とし穴 105）。
     * 送った値が悪いことは利用者にしか直せない。
     *
     * <p><strong>受け取った値を応答に載せない。</strong>
     * 送った本人は自分が送った値を知っている。載せると、
     * そのまま画面へ出す実装で反射型 XSS の材料になる。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "パラメータの形式が正しくありません: " + e.getName());
        problem.setType(URI.create("urn:kintai:error:validation-failed"));
        problem.setTitle("入力形式が不正です");
        problem.setProperty("errors", List.of(new FieldError(e.getName(), "形式が正しくありません")));
        return problem;
    }

    /**
     * 本文が JSON として読めない。送った側にしか直せないので 400 で返す。
     *
     * <p><strong>原因がドメイン例外なら、そちらへ渡す。</strong>
     * 本文の record にドメインの値オブジェクト（compact constructor つき）を
     * 置くと、Jackson が生成の失敗をこの例外に包む。
     * Spring は<strong>例外そのものの型で先に照合する</strong>ので、
     * 委譲しないと {@link #handleDomain} は一度も呼ばれず、
     * 業務エラーが理由の載らない 400 に化ける（CLAUDE.md 落とし穴 105）。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadableBody(HttpMessageNotReadableException e) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof DomainException domain) {
                return handleDomain(domain);
            }
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "要求の本文を読み取れません");
        problem.setType(URI.create("urn:kintai:error:validation-failed"));
        problem.setTitle("入力形式が不正です");
        return problem;
    }

    /**
     * {@code Content-Type} が違う。
     *
     * <p>付け忘れは日常的に起きるのに、変換していないと
     * {@code type} を持たない Spring 既定の JSON が返り、
     * 画面はそれを「不明なエラー」としか扱えない。
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ProblemDetail handleUnsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "この API は JSON だけを受け付けます");
        problem.setType(URI.create("urn:kintai:error:unsupported-media-type"));
        problem.setTitle("形式が違います");
        return problem;
    }

    /**
     * 引数そのものに付けた制約（{@code @Max} など）に反する。
     *
     * <p>{@code @RequestBody} の検証（{@link MethodArgumentNotValidException}）とは
     * <strong>別の例外</strong>である。片方だけ変換すると、
     * 同じ「入力が不正」が経路によって違う形で返る。
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    ProblemDetail handleParameterValidation(HandlerMethodValidationException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "パラメータが受け付けられる範囲を超えています");
        problem.setType(URI.create("urn:kintai:error:validation-failed"));
        problem.setTitle("入力形式が不正です");
        return problem;
    }

    /**
     * その URL に対応する API が無い。
     *
     * <p>これも既定では本文の無い 403 になる。
     * <strong>綴りの誤りが「権限が無い」と表示される</strong>ので、
     * 利用者も開発者も原因に辿り着けない。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail handleNoResource(NoResourceFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                "その URL の API はありません");
        problem.setType(URI.create("urn:kintai:error:resource-not-found"));
        problem.setTitle("見つかりません");
        return problem;
    }

    /**
     * その URL にその HTTP メソッドは無い。
     *
     * <p><strong>{@code Allow} を付ける。</strong> RFC 9110 が 405 に必須と定めている。
     * 本文だけを返すと、何なら使えるのかを総当たりで探すことになる。
     *
     * <p>反射しているのは要求行のメソッドだが、
     * Spring Security の {@code StrictHttpFirewall} が標準の 8 種類以外を
     * 先に弾くので、任意の文字列がここへ届くことはない。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.METHOD_NOT_ALLOWED,
                "その URL では %s を受け付けません".formatted(e.getMethod()));
        problem.setType(URI.create("urn:kintai:error:method-not-allowed"));
        problem.setTitle("使えないメソッドです");
        HttpHeaders headers = new HttpHeaders();
        Set<HttpMethod> allowed = e.getSupportedHttpMethods();
        if (allowed != null && !allowed.isEmpty()) {
            headers.setAllow(allowed);
        }
        return new ResponseEntity<>(problem, headers, HttpStatus.METHOD_NOT_ALLOWED);
    }

    /** 他の利用者が先に更新した。読んだ値で上書きすると相手の更新を黙って消す。 */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handleOptimisticLock(OptimisticLockingFailureException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "他の利用者が先に更新しました。読み込み直してからやり直してください");
        problem.setType(URI.create("urn:kintai:error:optimistic-lock-failure"));
        problem.setTitle("更新が競合しました");
        return problem;
    }

    /**
     * DB の制約による拒否。
     *
     * <p><strong>制約名やメッセージを応答に載せない。</strong>
     * テーブル名・列名・制約名は内部の構造であり、外に出す理由が無い。
     * 原因の特定はログで行う。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrity(DataIntegrityViolationException e) {
        log.warn("DB の制約に反する更新を拒否しました", e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "他のデータと矛盾するため保存できませんでした");
        problem.setType(URI.create("urn:kintai:error:constraint-violation"));
        problem.setTitle("データの整合性に反します");
        return problem;
    }

    /**
     * 実装の不備。
     *
     * <p>{@code IllegalArgumentException} / {@code IllegalStateException} は
     * 「業務エラーとして利用者に見せない」と決めたもの（アーキテクチャ設計書 6.2）。
     * <strong>メッセージを応答に載せず</strong>、ログへ出す。
     */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ProblemDetail handleImplementationDefect(RuntimeException e) {
        log.error("実装の不備により処理を継続できませんでした", e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "サーバ内部でエラーが発生しました");
        problem.setType(URI.create("urn:kintai:error:internal-error"));
        problem.setTitle("処理を継続できませんでした");
        return problem;
    }

    /** 項目ごとのエラー。{@code errors} 配列の要素。 */
    record FieldError(String field, String message) {
    }
}
