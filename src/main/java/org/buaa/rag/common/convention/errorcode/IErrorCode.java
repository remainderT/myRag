package org.buaa.rag.common.convention.errorcode;

/**
 * 错误码接口
 * 定义错误码的基本规范，所有错误码枚举需实现此接口
 */
public interface IErrorCode {

    /**
     * 错误码
     */
    String code();

    /**
     * 错误信息
     */
    String message();
}
