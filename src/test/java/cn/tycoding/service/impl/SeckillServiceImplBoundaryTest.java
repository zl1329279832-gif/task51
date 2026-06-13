package cn.tycoding.service.impl;

import cn.tycoding.dto.Exposer;
import cn.tycoding.dto.SeckillExecution;
import cn.tycoding.entity.Seckill;
import cn.tycoding.entity.SeckillOrder;
import cn.tycoding.exception.RepeatKillException;
import cn.tycoding.exception.SeckillCloseException;
import cn.tycoding.exception.SeckillException;
import cn.tycoding.mapper.SeckillMapper;
import cn.tycoding.mapper.SeckillOrderMapper;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.data.redis.core.BoundHashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.util.DigestUtils;

import java.math.BigDecimal;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 秒杀服务边界时间与缓存一致性测试
 */
@RunWith(MockitoJUnitRunner.class)
public class SeckillServiceImplBoundaryTest {

    @InjectMocks
    private SeckillServiceImpl seckillService;

    @Mock
    private SeckillMapper seckillMapper;

    @Mock
    private SeckillOrderMapper seckillOrderMapper;

    @Mock
    private RedisTemplate redisTemplate;

    @Mock
    private BoundHashOperations boundHashOperations;

    private static final long SECKILL_ID = 1L;
    private static final long USER_PHONE = 13700000000L;
    private static final BigDecimal MONEY = BigDecimal.valueOf(100.00);
    private static final String SALT = "sjajaspu-i-2jrfm;sd";

    @Before
    public void setUp() {
        when(redisTemplate.boundHashOps("seckill")).thenReturn(boundHashOperations);
    }

    private String getMD5(long seckillId) {
        String base = seckillId + "/" + SALT;
        return DigestUtils.md5DigestAsHex(base.getBytes());
    }

    private Seckill buildSeckill(long offsetStartMs, long offsetEndMs, long stockCount) {
        Seckill seckill = new Seckill();
        seckill.setSeckillId(SECKILL_ID);
        seckill.setTitle("Test Product");
        long now = System.currentTimeMillis();
        seckill.setStartTime(new Date(now + offsetStartMs));
        seckill.setEndTime(new Date(now + offsetEndMs));
        seckill.setStockCount(stockCount);
        seckill.setPrice(BigDecimal.valueOf(200));
        seckill.setCostPrice(BigDecimal.valueOf(100));
        return seckill;
    }

    // ===== exposer 边界测试 =====

    @Test
    public void exposer_notStarted_returnsFalse() {
        // 活动还没开始（startTime在10秒后）
        Seckill seckill = buildSeckill(10000, 20000, 100);
        when(seckillMapper.findById(SECKILL_ID)).thenReturn(seckill);

        Exposer exposer = seckillService.exportSeckillUrl(SECKILL_ID);

        assertFalse(exposer.isExposed());
        assertTrue(exposer.getNow() < exposer.getStart());
    }

    @Test
    public void exposer_justStarted_returnsTrue() {
        // 活动刚开始（startTime在过去1秒，endTime在未来10秒）
        Seckill seckill = buildSeckill(-1000, 10000, 100);
        when(seckillMapper.findById(SECKILL_ID)).thenReturn(seckill);

        Exposer exposer = seckillService.exportSeckillUrl(SECKILL_ID);

        assertTrue(exposer.isExposed());
        assertNotNull(exposer.getMd5());
        assertEquals(getMD5(SECKILL_ID), exposer.getMd5());
    }

    @Test
    public void exposer_justEnded_returnsFalse() {
        // 活动刚结束（endTime在过去1秒）
        Seckill seckill = buildSeckill(-20000, -1000, 100);
        when(seckillMapper.findById(SECKILL_ID)).thenReturn(seckill);

        Exposer exposer = seckillService.exportSeckillUrl(SECKILL_ID);

        assertFalse(exposer.isExposed());
        assertTrue(exposer.getNow() > exposer.getEnd());
    }

    @Test
    public void exposer_stockExhausted_returnsFalse() {
        // 活动进行中但库存为0
        Seckill seckill = buildSeckill(-10000, 10000, 0);
        when(seckillMapper.findById(SECKILL_ID)).thenReturn(seckill);

        Exposer exposer = seckillService.exportSeckillUrl(SECKILL_ID);

        assertFalse(exposer.isExposed());
    }

    @Test
    public void exposer_active_returnsTrue() {
        // 活动进行中，有库存
        Seckill seckill = buildSeckill(-10000, 10000, 50);
        when(seckillMapper.findById(SECKILL_ID)).thenReturn(seckill);

        Exposer exposer = seckillService.exportSeckillUrl(SECKILL_ID);

        assertTrue(exposer.isExposed());
        assertEquals(getMD5(SECKILL_ID), exposer.getMd5());
    }

    @Test
    public void exposer_notFound_returnsFalse() {
        when(seckillMapper.findById(SECKILL_ID)).thenReturn(null);

        Exposer exposer = seckillService.exportSeckillUrl(SECKILL_ID);

        assertFalse(exposer.isExposed());
    }

    @Test
    public void exposer_alwaysReadsFromDB_notFromStaleCache() {
        // 模拟：缓存中有旧数据（活动未开始），但数据库已更新（活动已开始）
        // 由于 exportSeckillUrl 始终从 DB 读取，应返回已开始的状态
        Seckill dbSeckill = buildSeckill(-1000, 10000, 100);
        when(seckillMapper.findById(SECKILL_ID)).thenReturn(dbSeckill);

        Exposer exposer = seckillService.exportSeckillUrl(SECKILL_ID);

        assertTrue(exposer.isExposed());
        // 验证确实调用了数据库
        verify(seckillMapper).findById(SECKILL_ID);
        // 验证刷新了缓存
        verify(boundHashOperations).put(eq(SECKILL_ID), eq(dbSeckill));
    }

    // ===== executeSeckill 边界测试 =====

    @Test(expected = SeckillCloseException.class)
    public void execute_afterEnded_throwsCloseException() {
        // 活动已结束，带旧md5调用
        String md5 = getMD5(SECKILL_ID);
        Seckill seckill = buildSeckill(-20000, -1000, 100);
        when(seckillMapper.findById(SECKILL_ID)).thenReturn(seckill);

        seckillService.executeSeckill(SECKILL_ID, MONEY, USER_PHONE, md5);
    }

    @Test(expected = SeckillCloseException.class)
    public void execute_notStarted_throwsCloseException() {
        // 活动未开始
        String md5 = getMD5(SECKILL_ID);
        Seckill seckill = buildSeckill(10000, 20000, 100);
        when(seckillMapper.findById(SECKILL_ID)).thenReturn(seckill);

        seckillService.executeSeckill(SECKILL_ID, MONEY, USER_PHONE, md5);
    }

    @Test(expected = SeckillCloseException.class)
    public void execute_stockExhausted_throwsCloseException() {
        // 库存为0
        String md5 = getMD5(SECKILL_ID);
        Seckill seckill = buildSeckill(-10000, 10000, 0);
        when(seckillMapper.findById(SECKILL_ID)).thenReturn(seckill);

        seckillService.executeSeckill(SECKILL_ID, MONEY, USER_PHONE, md5);
    }

    @Test(expected = SeckillCloseException.class)
    public void execute_reduceStockFails_throwsCloseException() {
        // 通过时间和库存检查，但reduceStock的SQL返回0（并发竞争导致）
        String md5 = getMD5(SECKILL_ID);
        Seckill seckill = buildSeckill(-10000, 10000, 1);
        when(seckillMapper.findById(SECKILL_ID)).thenReturn(seckill);
        when(seckillMapper.reduceStock(eq(SECKILL_ID), any(Date.class))).thenReturn(0);

        seckillService.executeSeckill(SECKILL_ID, MONEY, USER_PHONE, md5);
    }

    @Test(expected = RepeatKillException.class)
    public void execute_repeatKill_throwsRepeatKillException() {
        // 重复秒杀：减库存成功但插入订单返回0（唯一键冲突）
        String md5 = getMD5(SECKILL_ID);
        Seckill seckill = buildSeckill(-10000, 10000, 10);
        when(seckillMapper.findById(SECKILL_ID)).thenReturn(seckill);
        when(seckillMapper.reduceStock(eq(SECKILL_ID), any(Date.class))).thenReturn(1);
        when(seckillOrderMapper.insertOrder(eq(SECKILL_ID), eq(MONEY), eq(USER_PHONE))).thenReturn(0);

        seckillService.executeSeckill(SECKILL_ID, MONEY, USER_PHONE, md5);
    }

    @Test(expected = SeckillException.class)
    public void execute_invalidMd5_throwsSeckillException() {
        seckillService.executeSeckill(SECKILL_ID, MONEY, USER_PHONE, "invalid_md5");
    }

    @Test
    public void execute_success_returnsSuccessResult() {
        String md5 = getMD5(SECKILL_ID);
        Seckill seckill = buildSeckill(-10000, 10000, 10);
        SeckillOrder order = new SeckillOrder();
        order.setSeckillId(SECKILL_ID);
        order.setMoney(MONEY);

        when(seckillMapper.findById(SECKILL_ID)).thenReturn(seckill);
        when(seckillMapper.reduceStock(eq(SECKILL_ID), any(Date.class))).thenReturn(1);
        when(seckillOrderMapper.insertOrder(eq(SECKILL_ID), eq(MONEY), eq(USER_PHONE))).thenReturn(1);
        when(seckillOrderMapper.findById(SECKILL_ID, USER_PHONE)).thenReturn(order);

        SeckillExecution result = seckillService.executeSeckill(SECKILL_ID, MONEY, USER_PHONE, md5);

        assertEquals(1, result.getState());
        assertNotNull(result.getSeckillOrder());
    }

    @Test
    public void execute_success_refreshesCache() {
        String md5 = getMD5(SECKILL_ID);
        Seckill seckill = buildSeckill(-10000, 10000, 10);
        SeckillOrder order = new SeckillOrder();

        when(seckillMapper.findById(SECKILL_ID)).thenReturn(seckill);
        when(seckillMapper.reduceStock(eq(SECKILL_ID), any(Date.class))).thenReturn(1);
        when(seckillOrderMapper.insertOrder(eq(SECKILL_ID), eq(MONEY), eq(USER_PHONE))).thenReturn(1);
        when(seckillOrderMapper.findById(SECKILL_ID, USER_PHONE)).thenReturn(order);

        seckillService.executeSeckill(SECKILL_ID, MONEY, USER_PHONE, md5);

        // 验证成功后用DB数据刷新了缓存（findById被调用多次：校验+刷新缓存）
        verify(seckillMapper, atLeast(2)).findById(SECKILL_ID);
        verify(boundHashOperations, atLeastOnce()).put(eq(SECKILL_ID), any(Seckill.class));
        verify(redisTemplate, atLeastOnce()).expire(eq("seckill"), eq(60L), eq(TimeUnit.SECONDS));
    }

    @Test(expected = SeckillCloseException.class)
    public void execute_afterEnded_noStockDeducted() {
        // 活动结束后，即使md5正确也不应扣库存
        String md5 = getMD5(SECKILL_ID);
        Seckill seckill = buildSeckill(-20000, -1000, 10);
        when(seckillMapper.findById(SECKILL_ID)).thenReturn(seckill);

        try {
            seckillService.executeSeckill(SECKILL_ID, MONEY, USER_PHONE, md5);
        } finally {
            // 验证未调用reduceStock
            verify(seckillMapper, never()).reduceStock(anyLong(), any(Date.class));
            // 验证未插入订单
            verify(seckillOrderMapper, never()).insertOrder(anyLong(), any(BigDecimal.class), anyLong());
        }
    }

    // ===== findById 缓存一致性测试 =====

    @Test
    public void findById_cacheHit_returnsFromCache() {
        Seckill cached = buildSeckill(-10000, 10000, 50);
        when(boundHashOperations.get(SECKILL_ID)).thenReturn(cached);

        Seckill result = seckillService.findById(SECKILL_ID);

        assertEquals(cached, result);
        // 不应查询数据库
        verify(seckillMapper, never()).findById(SECKILL_ID);
    }

    @Test
    public void findById_cacheMiss_fetchesFromDbAndPopulatesCache() {
        Seckill dbSeckill = buildSeckill(-10000, 10000, 50);
        when(boundHashOperations.get(SECKILL_ID)).thenReturn(null);
        when(seckillMapper.findById(SECKILL_ID)).thenReturn(dbSeckill);

        Seckill result = seckillService.findById(SECKILL_ID);

        assertEquals(dbSeckill, result);
        verify(seckillMapper).findById(SECKILL_ID);
        verify(boundHashOperations).put(eq(SECKILL_ID), eq(dbSeckill));
        verify(redisTemplate).expire(eq("seckill"), eq(60L), eq(TimeUnit.SECONDS));
    }

    // ===== 缓存TTL过期场景测试 =====

    @Test
    public void exposer_cacheExpired_dbReturnsCorrectState() {
        // 模拟缓存过期后（缓存返回null），DB返回活动进行中的数据
        Seckill dbSeckill = buildSeckill(-5000, 10000, 30);
        when(seckillMapper.findById(SECKILL_ID)).thenReturn(dbSeckill);

        Exposer exposer = seckillService.exportSeckillUrl(SECKILL_ID);

        assertTrue(exposer.isExposed());
        // 验证重新写入了缓存
        verify(boundHashOperations).put(eq(SECKILL_ID), eq(dbSeckill));
        verify(redisTemplate).expire(eq("seckill"), eq(60L), eq(TimeUnit.SECONDS));
    }
}
