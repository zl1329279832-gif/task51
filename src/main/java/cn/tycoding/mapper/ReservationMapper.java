package cn.tycoding.mapper;

import cn.tycoding.entity.SeckillReservation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 秒杀预约提醒Mapper
 *
 * @auther TyCoding
 * @date 2018/10/8
 */
@Mapper
public interface ReservationMapper {

    /**
     * 插入预约记录（使用INSERT IGNORE防止重复预约）
     *
     * @param seckillId 秒杀商品ID
     * @param userPhone 用户手机号
     * @return 插入记录数，>=1成功，0表示重复
     */
    int insertReservation(@Param("seckillId") long seckillId, @Param("userPhone") long userPhone);

    /**
     * 删除预约记录
     *
     * @param seckillId 秒杀商品ID
     * @param userPhone 用户手机号
     * @return 删除记录数
     */
    int deleteReservation(@Param("seckillId") long seckillId, @Param("userPhone") long userPhone);

    /**
     * 查询用户的所有预约记录
     *
     * @param userPhone 用户手机号
     * @return 预约列表
     */
    List<SeckillReservation> findByUserPhone(@Param("userPhone") long userPhone);

    /**
     * 查询某个秒杀活动的待提醒预约记录（status=0）
     *
     * @param seckillId 秒杀商品ID
     * @return 待提醒预约列表
     */
    List<SeckillReservation> findPendingBySeckillId(@Param("seckillId") long seckillId);

    /**
     * 将某个秒杀活动的待提醒记录标记为已提醒（status=0 -> 1）
     *
     * @param seckillId 秒杀商品ID
     * @return 更新记录数
     */
    int markConsumedBySeckillId(@Param("seckillId") long seckillId);

    /**
     * 查询用户是否已预约
     *
     * @param seckillId 秒杀商品ID
     * @param userPhone 用户手机号
     * @return 预约记录，null表示未预约
     */
    SeckillReservation findBySeckillIdAndUserPhone(@Param("seckillId") long seckillId, @Param("userPhone") long userPhone);
}
