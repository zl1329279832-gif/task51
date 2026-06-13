package cn.tycoding.service.impl;

import cn.tycoding.entity.Seckill;
import cn.tycoding.entity.SeckillReservation;
import cn.tycoding.mapper.ReservationMapper;
import cn.tycoding.mapper.SeckillMapper;
import cn.tycoding.service.ReservationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 秒杀预约提醒服务实现
 */
@Service
public class ReservationServiceImpl implements ReservationService {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private static final String RESERVATION_KEY_PREFIX = "seckill:reservation:";

    @Autowired
    private SeckillMapper seckillMapper;

    @Autowired
    private ReservationMapper reservationMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Override
    public SeckillReservation reserve(long seckillId, long userPhone) {
        Seckill seckill = seckillMapper.findById(seckillId);
        if (seckill == null) {
            throw new RuntimeException("秒杀活动不存在");
        }

        Date now = new Date();
        if (now.getTime() > seckill.getEndTime().getTime()) {
            throw new RuntimeException("秒杀活动已结束，无法预约");
        }
        if (now.getTime() >= seckill.getStartTime().getTime()) {
            throw new RuntimeException("秒杀活动已开始，无需预约");
        }

        // Redis SET 快速判重
        String redisKey = RESERVATION_KEY_PREFIX + seckillId;
        Boolean isMember = redisTemplate.opsForSet().isMember(redisKey, userPhone);
        if (Boolean.TRUE.equals(isMember)) {
            throw new RuntimeException("不可重复预约");
        }

        // 写入数据库（INSERT IGNORE，唯一键冲突返回0）
        int insertCount = reservationMapper.insertReservation(seckillId, userPhone);
        if (insertCount <= 0) {
            // DB中已存在，补偿Redis
            redisTemplate.opsForSet().add(redisKey, userPhone);
            throw new RuntimeException("不可重复预约");
        }

        // 写入Redis SET
        redisTemplate.opsForSet().add(redisKey, userPhone);
        logger.info("用户[{}]预约秒杀场[{}]成功", userPhone, seckillId);

        return reservationMapper.findBySeckillIdAndUserPhone(seckillId, userPhone);
    }

    @Override
    public boolean cancelReservation(long seckillId, long userPhone) {
        Seckill seckill = seckillMapper.findById(seckillId);
        if (seckill == null) {
            throw new RuntimeException("秒杀活动不存在");
        }

        Date now = new Date();
        if (now.getTime() > seckill.getEndTime().getTime()) {
            throw new RuntimeException("秒杀活动已结束");
        }

        int deleteCount = reservationMapper.deleteReservation(seckillId, userPhone);

        // 从Redis SET移除
        String redisKey = RESERVATION_KEY_PREFIX + seckillId;
        redisTemplate.opsForSet().remove(redisKey, userPhone);

        logger.info("用户[{}]取消预约秒杀场[{}]，结果: {}", userPhone, seckillId, deleteCount > 0);
        return deleteCount > 0;
    }

    @Override
    public List<SeckillReservation> getUserReservations(long userPhone) {
        List<SeckillReservation> reservations = reservationMapper.findByUserPhone(userPhone);
        if (reservations == null || reservations.isEmpty()) {
            return Collections.emptyList();
        }

        // 包装为可变列表，过滤掉已结束的活动预约
        reservations = new java.util.ArrayList<>(reservations);
        Date now = new Date();
        reservations.removeIf(r -> {
            Seckill seckill = seckillMapper.findById(r.getSeckillId());
            return seckill == null || now.getTime() > seckill.getEndTime().getTime();
        });

        return reservations;
    }

    @Override
    public List<SeckillReservation> pullReminders(long seckillId) {
        Seckill seckill = seckillMapper.findById(seckillId);
        if (seckill == null) {
            throw new RuntimeException("秒杀活动不存在");
        }

        Date now = new Date();
        if (now.getTime() < seckill.getStartTime().getTime()) {
            throw new RuntimeException("秒杀活动未开始，无法拉取提醒");
        }
        if (now.getTime() > seckill.getEndTime().getTime()) {
            throw new RuntimeException("秒杀活动已结束");
        }

        // 查询待提醒记录
        List<SeckillReservation> pendingList = reservationMapper.findPendingBySeckillId(seckillId);
        if (pendingList == null || pendingList.isEmpty()) {
            logger.info("秒杀场[{}]无待提醒的预约记录", seckillId);
            return Collections.emptyList();
        }

        // 标记为已提醒（幂等：仅更新status=0的记录）
        int consumed = reservationMapper.markConsumedBySeckillId(seckillId);
        logger.info("秒杀场[{}]已消费{}条预约提醒记录", seckillId, consumed);

        return pendingList;
    }
}
