package cn.tycoding.exception;

/**
 * 秒杀未开始异常（运行期异常）
 * 当当前时间早于秒杀开始时间时抛出
 *
 * @auther TyCoding
 * @date 2018/10/8
 */
public class SeckillNotStartedException extends SeckillException {

    public SeckillNotStartedException(String message) {
        super(message);
    }

    public SeckillNotStartedException(String message, Throwable cause) {
        super(message, cause);
    }
}
