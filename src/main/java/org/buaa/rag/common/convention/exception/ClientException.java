package org.buaa.rag.common.convention.exception;

import org.buaa.rag.common.convention.errorcode.IErrorCode;
import org.buaa.rag.common.convention.errorcode.RagErrorCode;

import java.util.Optional;

/**
 * 客户端异常
 * 用于表示由客户端请求引起的错误（如参数校验失败、权限不足等）
 * HTTP状态码通常为 4xx
 */
public class ClientException extends AbstractException {

    /**
     * 使用错误码构造异常
     */
    public ClientException(IErrorCode errorCode) {
        this(null, null, errorCode);
    }

    /**
     * 使用自定义消息构造异常（使用默认客户端错误码）
     */
    public ClientException(String message) {
        this(message, null, RagErrorCode.CLIENT_ERROR);
    }

    /**
     * 使用自定义消息和错误码构造异常
     */
    public ClientException(String message, IErrorCode errorCode) {
        this(message, null, errorCode);
    }

    /**
     * 完整构造器
     *
     * @param message 自定义错误消息
     * @param throwable 原始异常
     * @param errorCode 错误码
     */
    public ClientException(String message, Throwable throwable, IErrorCode errorCode) {
        super(Optional.ofNullable(message).orElse(errorCode.message()), throwable, errorCode);
    }
}
