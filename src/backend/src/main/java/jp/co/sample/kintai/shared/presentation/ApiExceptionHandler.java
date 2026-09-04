package jp.co.sample.kintai.shared.presentation;

import java.net.URI;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
