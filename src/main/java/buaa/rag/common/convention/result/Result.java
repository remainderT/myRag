package buaa.rag.common.convention.result;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一返回对象
 * 所有API响应都应使用此类封装
 *
 * @param <T> 响应数据类型
 */
@Data
@Accessors(chain = true)
public class Result<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 成功状态码
     */
    public static final String SUCCESS_CODE = "0";

    /**
     * 返回码
     * "0" 表示成功，其他值表示错误（格式：A0xxx/B0xxx/C0xxx）
     */
    private String code;

    /**
     * 返回消息
     */
    private String message;

    /**
     * 响应数据
     */
    private T data;

    /**
     * 判断是否成功
     *
     * @return true 成功，false 失败
     */
    public boolean isSuccess() {
        return SUCCESS_CODE.equals(code);
    }
}
