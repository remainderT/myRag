package buaa.rag.common.convention.exception;

import buaa.rag.common.convention.errorcode.IErrorCode;
import buaa.rag.common.convention.errorcode.RagErrorCode;

import java.util.Optional;

/**
 * 服务端异常
 * 用于表示由服务端内部错误引起的问题（如数据库异常、外部服务调用失败等）
 * HTTP状态码通常为 5xx
 */
public class ServiceException extends AbstractException {

    /**
     * 使用错误码构造异常
     */
    public ServiceException(IErrorCode errorCode) {
        this(null, null, errorCode);
    }

    /**
     * 使用自定义消息构造异常（使用默认服务端错误码）
     */
    public ServiceException(String message) {
        this(message, null, RagErrorCode.SERVICE_ERROR);
    }

    /**
     * 使用自定义消息和错误码构造异常
     */
    public ServiceException(String message, IErrorCode errorCode) {
        this(message, null, errorCode);
    }

    /**
     * 完整构造器
     *
     * @param message 自定义错误消息
     * @param throwable 原始异常
     * @param errorCode 错误码
     */
    public ServiceException(String message, Throwable throwable, IErrorCode errorCode) {
        super(Optional.ofNullable(message).orElse(errorCode.message()), throwable, errorCode);
    }
}
