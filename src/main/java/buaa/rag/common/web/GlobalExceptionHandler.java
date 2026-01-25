package buaa.rag.common.web;

import buaa.rag.common.convention.errorcode.RagErrorCode;
import buaa.rag.common.convention.exception.AbstractException;
import buaa.rag.common.convention.result.Result;
import buaa.rag.common.convention.result.Results;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 * 统一捕获并处理所有Controller抛出的异常
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理参数校验异常（Bean Validation）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidationException(MethodArgumentNotValidException ex, HttpServletRequest request) {
        FieldError firstError = ex.getBindingResult().getFieldError();
        String errorMessage = firstError != null ? firstError.getDefaultMessage() : "参数校验失败";

        log.error("[{}] {} - 参数校验失败: {}",
                request.getMethod(),
                getFullRequestUrl(request),
                errorMessage);

        return Results.failure(RagErrorCode.PARAM_INVALID.code(), errorMessage);
    }

    /**
     * 处理业务异常（ClientException / ServiceException）
     */
    @ExceptionHandler(AbstractException.class)
    public Result<Void> handleAbstractException(AbstractException ex, HttpServletRequest request) {
        // 如果有原始异常，打印完整堆栈；否则只打印错误信息
        if (ex.getCause() != null) {
            log.error("[{}] {} - 业务异常: {} ({})",
                    request.getMethod(),
                    getFullRequestUrl(request),
                    ex.getErrorMessage(),
                    ex.getErrorCode(),
                    ex);
        } else {
            log.error("[{}] {} - 业务异常: {} ({})",
                    request.getMethod(),
                    getFullRequestUrl(request),
                    ex.getErrorMessage(),
                    ex.getErrorCode());
        }

        return Results.failure(ex);
    }

    /**
     * 处理未捕获的异常（兜底处理）
     */
    @ExceptionHandler(Throwable.class)
    public Result<Void> handleThrowable(Throwable throwable, HttpServletRequest request) {
        log.error("[{}] {} - 系统异常",
                request.getMethod(),
                getFullRequestUrl(request),
                throwable);

        // 返回通用服务端错误，避免暴露内部异常细节
        String errorCode = RagErrorCode.SERVICE_ERROR.code();
        String errorMessage = RagErrorCode.SERVICE_ERROR.message();

        return Results.failure(errorCode, errorMessage);
    }

    /**
     * 获取完整请求URL（包含查询参数）
     */
    private String getFullRequestUrl(HttpServletRequest request) {
        String queryString = request.getQueryString();
        return request.getRequestURI() +
                (queryString != null ? "?" + queryString : "");
    }
}
