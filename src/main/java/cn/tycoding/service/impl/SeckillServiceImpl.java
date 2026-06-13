package cn.tycoding.service.impl;

import cn.tycoding.dto.Exposer;
import cn.tycoding.dto.SeckillExecution;
import cn.tycoding.entity.Seckill;
import cn.tycoding.entity.SeckillOrder;
import cn.tycoding.enums.SeckillStatEnum;
import cn.tycoding.exception.RepeatKillException;
import cn.tycoding.exception.SeckillCloseException;
import cn.tycoding.exception.SeckillException;
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
    //缓存过期时间（秒），防止缓存与数据库不一致
    private static final long CACHE_TTL_SECONDS = 60;

    @Autowired
    private SeckillMapper seckillMapper;

    @Autowired
    private SeckillOrderMapper seckillOrderMapper;

    @Autowired
    private RedisTemplate redisTemplate;

    @Override
    public List<Seckill> findAll() {
        List<Seckill> seckillList = redisTemplate.boundHashOps(key).values();
        if (seckillList == null || seckillList.size() == 0){
            //说明缓存中没有秒杀列表数据
            //查询数据库中秒杀列表数据，并将列表数据循环放入redis缓存中
            seckillList = seckillMapper.findAll();
            for (Seckill seckill : seckillList){
                //将秒杀列表数据依次放入redis缓存中，key:秒杀表的ID值；value:秒杀商品数据
                redisTemplate.boundHashOps(key).put(seckill.getSeckillId(), seckill);
            }
            //设置缓存过期时间，防止与数据库数据不一致
            redisTemplate.expire(key, CACHE_TTL_SECONDS, TimeUnit.SECONDS);
            logger.info("findAll -> 从数据库中读取放入缓存中");
        }else{
            logger.info("findAll -> 从缓存中读取");
        }
        return seckillList;
    }

    @Override
    public Seckill findById(long seckillId) {
        //优先从缓存读取，保证详情页与exposer数据源一致
        Seckill seckill = (Seckill) redisTemplate.boundHashOps(key).get(seckillId);
        if (seckill == null) {
            seckill = seckillMapper.findById(seckillId);
            if (seckill != null) {
                redisTemplate.boundHashOps(key).put(seckill.getSeckillId(), seckill);
                redisTemplate.expire(key, CACHE_TTL_SECONDS, TimeUnit.SECONDS);
            }
        }
        return seckill;
    }

    @Override
    public Exposer exportSeckillUrl(long seckillId) {
        //时间边界判断必须从数据库读取，避免缓存中时间字段过期导致状态不准
        Seckill seckill = seckillMapper.findById(seckillId);
        if (seckill == null) {
            return new Exposer(false, seckillId);
        }
        //用数据库最新数据刷新缓存
        redisTemplate.boundHashOps(key).put(seckill.getSeckillId(), seckill);
        redisTemplate.expire(key, CACHE_TTL_SECONDS, TimeUnit.SECONDS);

        Date startTime = seckill.getStartTime();
        Date endTime = seckill.getEndTime();
        Date nowTime = new Date();

        if (nowTime.getTime() < startTime.getTime() || nowTime.getTime() > endTime.getTime()) {
            return new Exposer(false, seckillId, nowTime.getTime(), startTime.getTime(), endTime.getTime());
        }

        //库存检查：库存耗尽时不再暴露秒杀地址
        if (seckill.getStockCount() <= 0) {
            return new Exposer(false, seckillId, nowTime.getTime(), startTime.getTime(), endTime.getTime());
        }

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

        Date nowTime = new Date();

        try {
            //先从数据库校验时间窗口和库存，防止活动结束后仍可用旧md5扣库存
            Seckill seckill = seckillMapper.findById(seckillId);
            if (seckill == null) {
                throw new SeckillCloseException("seckill is closed");
            }
            if (nowTime.getTime() < seckill.getStartTime().getTime()
                    || nowTime.getTime() > seckill.getEndTime().getTime()) {
                throw new SeckillCloseException("seckill is closed");
            }
            if (seckill.getStockCount() <= 0) {
                throw new SeckillCloseException("seckill is closed");
            }

            //先减库存（SQL中包含时间与库存的二次校验）
            int updateCount = seckillMapper.reduceStock(seckillId, nowTime);
            if (updateCount <= 0) {
                throw new SeckillCloseException("seckill is closed");
            }

            //再记录秒杀订单信息
            //唯一性：seckillId,userPhone，保证一个用户只能秒杀一件商品
            int insertCount = seckillOrderMapper.insertOrder(seckillId, money, userPhone);
            if (insertCount <= 0) {
                //重复秒杀
                throw new RepeatKillException("seckill repeated");
            }

            //秒杀成功
            SeckillOrder seckillOrder = seckillOrderMapper.findById(seckillId, userPhone);

            //更新缓存（用数据库最新库存刷新，避免缓存与数据库不一致）
            Seckill updated = seckillMapper.findById(seckillId);
            if (updated != null) {
                redisTemplate.boundHashOps(key).put(seckillId, updated);
                redisTemplate.expire(key, CACHE_TTL_SECONDS, TimeUnit.SECONDS);
            }

            return new SeckillExecution(seckillId, SeckillStatEnum.SUCCESS, seckillOrder);
        } catch (SeckillCloseException e) {
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
