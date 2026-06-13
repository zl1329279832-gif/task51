package cn.tycoding.service;

import cn.tycoding.entity.SeckillReservation;

import java.util.List;

/**
 * 秒杀预约提醒服务接口
 */
public interface ReservationService {

    /**
     * 预约秒杀提醒（活动未开始时才允许预约）
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
     * @return 是否成功取消
     */
    boolean cancelReservation(long seckillId, long userPhone);

    /**
     * 查询用户的所有有效预约（过滤已结束的活动）
     *
     * @param userPhone 用户手机号
     * @return 有效预约列表
     */
    List<SeckillReservation> getUserReservations(long userPhone);

    /**
     * 拉取待提醒的预约记录（活动进入可抢时间窗后调用，幂等消费）
     *
     * @param seckillId 秒杀商品ID
     * @return 待提醒的预约列表
     */
    List<SeckillReservation> pullReminders(long seckillId);
}
