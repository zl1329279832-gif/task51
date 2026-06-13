package cn.tycoding.service.impl;

import cn.tycoding.dto.Exposer;
import cn.tycoding.dto.SeckillExecution;
import cn.tycoding.entity.Seckill;
import cn.tycoding.entity.SeckillOrder;
import cn.tycoding.enums.SeckillStatEnum;
import cn.tycoding.exception.RepeatKillException;
import cn.tycoding.exception.SeckillCloseException;
import cn.tycoding.exception.SeckillException;
import cn.tycoding.exception.SeckillNotStartedException;
import cn.tycoding.mapper.SeckillMapper;
import cn.tycoding.mapper.SeckillOrderMapper;
import cn.tycoding.service.SeckillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @auther TyCoding
 * @date 2018/10/6
 */
@Service
public class SeckillServiceImpl implements SeckillService {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    //设置盐值字符串，随便定义，用于混淆MD5值
    private final String salt = "sjajaspu-i-2jrfm;sd";
    //设置秒杀redis缓存的key
    private final String key = "seckill";

    // 秒杀列表缓存过期时间（秒）：5分钟
    private static final int SECKILL_LIST_TTL_SECONDS = 300;
    // 单个秒杀商品缓存过期时间（秒）：1分钟
    private static final int SECKILL_ITEM_TTL_SECONDS = 60;

    @Autowired
    private SeckillMapper seckillMapper;

    @Autowired
    private SeckillOrderMapper seckillOrderMapper;

    @Autowired
    private RedisTemplate redisTemplate;

    @Override
    public List<Seckill> findAll() {
        List<Seckill> seckillList = redisTemplate.boundHashOps("seckill").values();
        if (seckillList == null || seckillList.isEmpty()) {
            //说明缓存中没有秒杀列表数据
            //查询数据库中秒杀列表数据，并将列表数据循环放入redis缓存中
            seckillList = seckillMapper.findAll();
            for (Seckill seckill : seckillList) {
                //将秒杀列表数据依次放入redis缓存中，key:秒杀表的ID值；value:秒杀商品数据
                redisTemplate.boundHashOps(key).put(seckill.getSeckillId(), seckill);
            }
            // 设置缓存过期时间，防止缓存永不过期导致数据不一致
            redisTemplate.expire(key, SECKILL_LIST_TTL_SECONDS, TimeUnit.SECONDS);
            logger.info("findAll -> 从数据库中读取放入缓存中，TTL={}s", SECKILL_LIST_TTL_SECONDS);
        } else {
            logger.info("findAll -> 从缓存中读取");
        }
        return seckillList;
    }

    @Override
    public Seckill findById(long seckillId) {
        return seckillMapper.findById(seckillId);
    }

    @Override
    public Exposer exportSeckillUrl(long seckillId) {
        Seckill seckill = (Seckill) redisTemplate.boundHashOps(key).get(seckillId);
        if (seckill == null) {
            //说明redis缓存中没有此key对应的value
            //查询数据库，并将数据放入缓存中
            seckill = seckillMapper.findById(seckillId);
            if (seckill == null) {
                //说明没有查询到
                return new Exposer(false, seckillId);
            } else {
                //查询到了，存入redis缓存中。 key:秒杀表的ID值； value:秒杀表数据
                redisTemplate.boundHashOps(key).put(seckill.getSeckillId(), seckill);
                // 设置缓存过期时间，确保时间窗口和库存数据不会永久过期
                redisTemplate.expire(key, SECKILL_ITEM_TTL_SECONDS, TimeUnit.SECONDS);
                logger.info("exportSeckillUrl -> 从数据库中读取并放入缓存中，TTL={}s", SECKILL_ITEM_TTL_SECONDS);
            }
        } else {
            logger.info("exportSeckillUrl -> 从缓存中读取");
        }

        Date startTime = seckill.getStartTime();
        Date endTime = seckill.getEndTime();
        // 使用System.currentTimeMillis()获取更精确的系统时间
        long nowTime = System.currentTimeMillis();
        // 时间窗口判断：startTime <= now <= endTime 时秒杀开启
        if (nowTime < startTime.getTime() || nowTime > endTime.getTime()) {
            return new Exposer(false, seckillId, nowTime, startTime.getTime(), endTime.getTime());
        }
        //转换特定字符串的过程，不可逆的算法
        String md5 = getMD5(seckillId);
        return new Exposer(true, md5, seckillId);
    }

    //生成MD5值
    private String getMD5(Long seckillId) {
        String base = seckillId + "/" + salt;
        String md5 = DigestUtils.md5DigestAsHex(base.getBytes());
        return md5;
    }


    /**
     * 使用注解式事务方法的有优点：开发团队达成了一致约定，明确标注事务方法的编程风格
     * 使用事务控制需要注意：
     * 1.保证事务方法的执行时间尽可能短，不要穿插其他网络操作PRC/HTTP请求（可以将这些请求剥离出来）
     * 2.不是所有的方法都需要事务控制，如只有一条修改的操作、只读操作等是不需要进行事务控制的
     * <p>
     * Spring默认只对运行期异常进行事务的回滚操作，对于编译异常Spring是不进行回滚的，所以对于需要进行事务控制的方法尽可能将可能抛出的异常都转换成运行期异常
     */
    @Override
    @Transactional
    public SeckillExecution executeSeckill(long seckillId, BigDecimal money, long userPhone, String md5)
            throws SeckillException, RepeatKillException, SeckillCloseException {
        if (md5 == null || !md5.equals(getMD5(seckillId))) {
            throw new SeckillException("seckill data rewrite");
        }

        // Java端时间窗口校验：在执行数据库操作前先判断时间是否有效，
        // 防止活动结束后旧MD5仍可通过校验并触发数据库操作
        long nowTime = System.currentTimeMillis();
        Seckill cached = (Seckill) redisTemplate.boundHashOps(key).get(seckillId);
        if (cached != null) {
            if (nowTime < cached.getStartTime().getTime()) {
                throw new SeckillNotStartedException("seckill not started");
            }
            if (nowTime > cached.getEndTime().getTime()) {
                throw new SeckillCloseException("seckill is closed");
            }
        }
        // 如果缓存中没有数据，依赖后续SQL中reduceStock的时间窗口条件兜底

        //执行秒杀逻辑：1.储存秒杀订单；2.减库存
        Date killTime = new Date(nowTime);

        try {
            //记录秒杀订单信息
            int insertCount = seckillOrderMapper.insertOrder(seckillId, money, userPhone);
            //唯一性：seckillId,userPhone，保证一个用户只能秒杀一件商品
            if (insertCount <= 0) {
                //重复秒杀
                throw new RepeatKillException("seckill repeated");
            } else {
                //减库存
                int updateCount = seckillMapper.reduceStock(seckillId, killTime);
                if (updateCount <= 0) {
                    //没有更新记录，秒杀结束（库存为0或不在时间窗口内）
                    throw new SeckillCloseException("seckill is closed");
                } else {
                    //秒杀成功
                    SeckillOrder seckillOrder = seckillOrderMapper.findById(seckillId, userPhone);

                    //更新缓存（更新库存数量）—— 修复原bug: 使用stockCount而非seckillId
                    if (cached != null) {
                        cached.setStockCount(cached.getStockCount() - 1);
                        redisTemplate.boundHashOps(key).put(seckillId, cached);
                    }

                    return new SeckillExecution(seckillId, SeckillStatEnum.SUCCESS, seckillOrder);
                }
            }
        } catch (SeckillCloseException e) {
            throw e;
        } catch (SeckillNotStartedException e) {
            throw e;
        } catch (RepeatKillException e) {
            throw e;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            //所有编译期异常，转换为运行期异常
            throw new SeckillException("seckill inner error:" + e.getMessage());
        }
    }
}
