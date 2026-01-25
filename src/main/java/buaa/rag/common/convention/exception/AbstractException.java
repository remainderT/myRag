package buaa.rag.common.convention.exception;

import buaa.rag.common.convention.errorcode.IErrorCode;
import lombok.Getter;

import java.util.Optional;

/**
 * 抽象异常基类
 * 所有业务异常都应继承此类
 */
@Getter
public abstract class AbstractException extends RuntimeException {

    /**
     * 错误码
     */
    private final String errorCode;

    /**
     * 错误信息
     */
    private final String errorMessage;

    /**
     * 构造异常
     *
     * @param message 自定义错误信息（为空时使用 errorCode.message()）
     * @param throwable 原始异常
     * @param errorCode 错误码枚举
     */
    protected AbstractException(String message, Throwable throwable, IErrorCode errorCode) {
        super(message, throwable);
        this.errorCode = errorCode.code();
        this.errorMessage = Optional.ofNullable(message).orElse(errorCode.message());
    }
}
