package cn.tycoding.service;

import cn.tycoding.entity.SeckillReservation;

import java.util.List;

/**
 * 秒杀预约提醒业务接口
 *
 * @auther TyCoding
 * @date 2018/10/8
 */
public interface ReservationService {

    /**
     * 预约秒杀提醒
     * 仅在秒杀活动尚未开始时允许预约，活动已结束则拒绝
     *
     * @param seckillId 秒杀商品ID
     * @param userPhone 用户手机号
     * @return 预约记录
     */
    SeckillReservation reserve(long seckillId, long userPhone);

    /**
     * 取消预约
     *
     * @param seckillId 秒杀商品ID
     * @param userPhone 用户手机号
     * @return 是否取消成功
     */
    boolean cancelReservation(long seckillId, long userPhone);

    /**
     * 查询用户的所有预约记录
     * 活动结束后（当前时间 > endTime）的预约自动失效，不会返回
     *
     * @param userPhone 用户手机号
     * @return 有效预约列表
     */
    List<SeckillReservation> getUserReservations(long userPhone);

    /**
     * 拉取某个秒杀活动的待提醒预约记录
     * 仅在秒杀进入可抢时间窗（now >= startTime && now <= endTime）后允许拉取
     * 拉取后标记为已提醒，保证幂等消费
     *
     * @param seckillId 秒杀商品ID
     * @return 待提醒的预约记录列表
     */
    List<SeckillReservation> pullReminders(long seckillId);
}
