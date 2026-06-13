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
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 秒杀预约提醒业务实现
 *
 * 设计说明：
 * 1. 预约不能绕过原有MD5暴露地址和重复秒杀校验（预约仅做提醒，不影响秒杀流程）
 * 2. 活动结束后预约自动失效（getUserReservations过滤掉已结束的活动）
 * 3. 提醒消费幂等：pullReminders使用@Transactional + 原子UPDATE保证同一提醒只拉取一次
 * 4. Redis Set用于快速判断用户是否已预约某秒杀活动，避免重复提交
 *
 * @auther TyCoding
 * @date 2018/10/8
 */
@Service
public class ReservationServiceImpl implements ReservationService {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    //Redis中预约集合的key前缀
    private static final String RESERVATION_KEY_PREFIX = "seckill:reservation:";

    @Autowired
    private SeckillMapper seckillMapper;

    @Autowired
    private ReservationMapper reservationMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Override
    public SeckillReservation reserve(long seckillId, long userPhone) {
        //1.查询秒杀商品是否存在
        Seckill seckill = seckillMapper.findById(seckillId);
        if (seckill == null) {
            throw new RuntimeException("秒杀活动不存在");
        }

        Date now = new Date();

        //2.活动已结束，不允许预约
        if (now.getTime() > seckill.getEndTime().getTime()) {
            throw new RuntimeException("活动已结束");
        }

        //3.活动已开始（在进行中），不需要预约提醒
        if (now.getTime() >= seckill.getStartTime().getTime()) {
            throw new RuntimeException("活动已开始，无需预约");
        }

        //4.Redis快速检查是否重复预约
        String redisKey = RESERVATION_KEY_PREFIX + seckillId;
        Boolean isMember = redisTemplate.opsForSet().isMember(redisKey, userPhone);
        if (Boolean.TRUE.equals(isMember)) {
            throw new RuntimeException("请勿重复预约");
        }

        //5.数据库层再次校验（INSERT IGNORE + 唯一索引双重保障）
        int insertCount = reservationMapper.insertReservation(seckillId, userPhone);
        if (insertCount <= 0) {
            //唯一索引冲突，说明已预约，同步Redis缓存
            redisTemplate.opsForSet().add(redisKey, userPhone);
            throw new RuntimeException("请勿重复预约");
        }

        //6.写入Redis缓存
        redisTemplate.opsForSet().add(redisKey, userPhone);
        logger.info("用户[{}]预约秒杀活动[{}]成功", userPhone, seckillId);

        return reservationMapper.findBySeckillIdAndUserPhone(seckillId, userPhone);
    }

    @Override
    public boolean cancelReservation(long seckillId, long userPhone) {
        //1.查询秒杀商品是否存在
        Seckill seckill = seckillMapper.findById(seckillId);
        if (seckill == null) {
            throw new RuntimeException("秒杀活动不存在");
        }

        //2.活动已结束，无需取消
        Date now = new Date();
        if (now.getTime() > seckill.getEndTime().getTime()) {
            throw new RuntimeException("活动已结束");
        }

        //3.删除数据库记录
        int deleteCount = reservationMapper.deleteReservation(seckillId, userPhone);

        //4.清除Redis缓存
        String redisKey = RESERVATION_KEY_PREFIX + seckillId;
        redisTemplate.opsForSet().remove(redisKey, userPhone);

        logger.info("用户[{}]取消秒杀活动[{}]预约，结果：{}", userPhone, seckillId, deleteCount > 0);
        return deleteCount > 0;
    }

    @Override
    public List<SeckillReservation> getUserReservations(long userPhone) {
        List<SeckillReservation> reservations = reservationMapper.findByUserPhone(userPhone);
        if (reservations == null || reservations.isEmpty()) {
            return Collections.emptyList();
        }

        Date now = new Date();
        //过滤掉已结束的活动的预约（活动结束自动失效）
        reservations.removeIf(r -> {
            Seckill seckill = seckillMapper.findById(r.getSeckillId());
            return seckill == null || now.getTime() > seckill.getEndTime().getTime();
        });

        return reservations;
    }

    @Override
    @Transactional
    public List<SeckillReservation> pullReminders(long seckillId) {
        //1.查询秒杀商品是否存在
        Seckill seckill = seckillMapper.findById(seckillId);
        if (seckill == null) {
            throw new RuntimeException("秒杀活动不存在");
        }

        Date now = new Date();

        //2.活动尚未开始，不能拉取提醒
        if (now.getTime() < seckill.getStartTime().getTime()) {
            throw new RuntimeException("活动尚未开始");
        }

        //3.活动已结束，不能拉取提醒
        if (now.getTime() > seckill.getEndTime().getTime()) {
            throw new RuntimeException("活动已结束");
        }

        //4.查询待提醒的预约记录（status=0）
        List<SeckillReservation> pendingList = reservationMapper.findPendingBySeckillId(seckillId);
        if (pendingList == null || pendingList.isEmpty()) {
            logger.info("秒杀活动[{}]无待提醒的预约记录", seckillId);
            return Collections.emptyList();
        }

        //5.原子标记为已提醒（status 0->1），保证幂等消费
        //  在同一事务内，先查后更新，第二次调用时findPendingBySeckillId返回空
        int updateCount = reservationMapper.markConsumedBySeckillId(seckillId);
        logger.info("秒杀活动[{}]拉取并标记{}条提醒记录", seckillId, updateCount);

        return pendingList;
    }
}
