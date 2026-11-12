package com.example.cryptomonitor.common.api;

import com.example.cryptomonitor.collection.controller.CollectionRunController;
import com.example.cryptomonitor.market.controller.LatestQuoteController;
import com.example.cryptomonitor.market.controller.QuoteHistoryController;
import org.hibernate.exception.JDBCConnectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.SQLNonTransientConnectionException;

/** 業務参照APIに限った共通エラー変換。Actuator等の既存エラー処理には適用しない。 */
@RestControllerAdvice(assignableTypes = {LatestQuoteController.class, CollectionRunController.class, QuoteHistoryController.class})
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 入力検証で生成した安全な説明だけを400で返す。 */
    @ExceptionHandler(InvalidApiParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidParameter(InvalidApiParameterException exception) {
        return ResponseEntity.badRequest().body(new ApiErrorResponse("INVALID_PARAMETER", exception.getMessage()));
    }

    /**
     * Tx開始時などのラッパーも原因をたどる。接続障害と判別できる場合だけ503にし、
     * JPA/SQLのバグまで一律503にしない。応答文は固定し、内部原因は調査ログに残す。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleFailure(Exception exception) {
        if (isDatabaseConnectionFailure(exception)) {
            log.error("Reference API database connection failed", exception);
            return ResponseEntity.status(503).body(new ApiErrorResponse(
                    "SERVICE_UNAVAILABLE", "現在データを取得できません。時間をおいて再度お試しください"));
        }
        log.error("Reference API internal error", exception);
        return ResponseEntity.internalServerError().body(new ApiErrorResponse(
                "INTERNAL_ERROR", "データの取得中にエラーが発生しました"));
    }

    /** SQLStateの08系は接続例外。SQL構文不正・制約違反等の状態を接続不能に分類しない。 */
    private boolean isDatabaseConnectionFailure(Throwable exception) {
        for (Throwable currentCause = exception; currentCause != null; currentCause = currentCause.getCause()) {
            if (currentCause instanceof CannotGetJdbcConnectionException
                    || currentCause instanceof JDBCConnectionException
                    || currentCause instanceof SQLTransientConnectionException
                    || currentCause instanceof SQLNonTransientConnectionException) return true;
            if (currentCause instanceof SQLException sqlException
                    && sqlException.getSQLState() != null && sqlException.getSQLState().startsWith("08")) return true;
        }
        return false;
    }
}
