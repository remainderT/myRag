package buaa.rag.common.convention.result;

import buaa.rag.common.convention.errorcode.IErrorCode;
import buaa.rag.common.convention.errorcode.RagErrorCode;
import buaa.rag.common.convention.exception.AbstractException;

import java.util.Optional;

/**
 * 返回对象构造工具类
 * 提供便捷的方法来创建统一格式的响应对象
 */
public final class Results {

    private Results() {
        // 工具类不允许实例化
    }

    /**
     * 构造成功响应（无数据）
     */
    public static Result<Void> success() {
        return new Result<Void>()
                .setCode(Result.SUCCESS_CODE)
                .setMessage("操作成功");
    }

    /**
     * 构造成功响应（带数据）
     *
     * @param data 响应数据
     * @param <T> 数据类型
     */
    public static <T> Result<T> success(T data) {
        return new Result<T>()
                .setCode(Result.SUCCESS_CODE)
                .setMessage("操作成功")
                .setData(data);
    }

    /**
     * 构造失败响应（使用默认服务端错误）
     */
    public static Result<Void> failure() {
        return new Result<Void>()
                .setCode(RagErrorCode.SERVICE_ERROR.code())
                .setMessage(RagErrorCode.SERVICE_ERROR.message());
    }

    /**
     * 构造失败响应（从业务异常）
     *
     * @param exception 业务异常
     */
    public static Result<Void> failure(AbstractException exception) {
        return new Result<Void>()
                .setCode(Optional.ofNullable(exception.getErrorCode())
                        .orElse(RagErrorCode.SERVICE_ERROR.code()))
                .setMessage(Optional.ofNullable(exception.getErrorMessage())
                        .orElse(RagErrorCode.SERVICE_ERROR.message()));
    }

    /**
     * 构造失败响应（使用错误码和自定义消息）
     *
     * @param errorCode 错误码
     * @param errorMessage 错误消息
     */
    public static Result<Void> failure(String errorCode, String errorMessage) {
        return new Result<Void>()
                .setCode(errorCode)
                .setMessage(errorMessage);
    }

    /**
     * 构造失败响应（使用错误码枚举）
     *
     * @param errorCode 错误码枚举
     */
    public static Result<Void> failure(IErrorCode errorCode) {
        return new Result<Void>()
                .setCode(errorCode.code())
                .setMessage(errorCode.message());
    }
}
