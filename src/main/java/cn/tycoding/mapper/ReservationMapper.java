package cn.tycoding.mapper;

import cn.tycoding.entity.SeckillReservation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 秒杀预约提醒Mapper
 */
@Mapper
public interface ReservationMapper {

    /**
     * 插入预约记录，使用INSERT IGNORE防止唯一键冲突
     */
    int insertReservation(@Param("seckillId") long seckillId, @Param("userPhone") long userPhone);

    /**
     * 删除预约记录（取消预约）
     */
    int deleteReservation(@Param("seckillId") long seckillId, @Param("userPhone") long userPhone);

    /**
     * 查询用户的所有预约记录
     */
    List<SeckillReservation> findByUserPhone(@Param("userPhone") long userPhone);

    /**
     * 查询某个秒杀活动的待提醒预约记录（status=0）
     */
    List<SeckillReservation> findPendingBySeckillId(@Param("seckillId") long seckillId);

    /**
     * 将某个秒杀活动的待提醒记录标记为已提醒（status=0 -> 1），幂等消费
     */
    int markConsumedBySeckillId(@Param("seckillId") long seckillId);

    /**
     * 查询用户是否已预约某个秒杀活动
     */
    SeckillReservation findBySeckillIdAndUserPhone(@Param("seckillId") long seckillId, @Param("userPhone") long userPhone);
}
