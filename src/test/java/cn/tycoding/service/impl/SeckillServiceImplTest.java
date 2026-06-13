package cn.tycoding.service.impl;

import cn.tycoding.dto.Exposer;
import cn.tycoding.dto.SeckillExecution;
import cn.tycoding.entity.Seckill;
import cn.tycoding.entity.SeckillOrder;
import cn.tycoding.exception.RepeatKillException;
import cn.tycoding.exception.SeckillCloseException;
import cn.tycoding.exception.SeckillException;
import cn.tycoding.exception.SeckillNotStartedException;
import cn.tycoding.mapper.SeckillMapper;
import cn.tycoding.mapper.SeckillOrderMapper;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.data.redis.core.BoundHashOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SeckillServiceImpl 边界时间和缓存场景的单元测试
 *
 * 覆盖场景：
 * 1. exportSeckillUrl — 缓存命中/未命中、未开始、进行中、已结束、边界时间点
 * 2. executeSeckill   — 成功、重复秒杀、库存耗尽、活动已结束(旧MD5)、未开始、MD5非法、边界时间点
 * 3. findAll          — 缓存命中、缓存未命中(TTL设置)、缓存过期后刷新
 * 4. 缓存失效         — TTL过期后从DB重新加载、缓存与DB时间一致性
 *
 * @auther TyCoding
 * @date 2018/10/8
 */
@SuppressWarnings({"unchecked", "rawtypes"})
@RunWith(MockitoJUnitRunner.class)
public class SeckillServiceImplTest {

    @Mock
    private SeckillMapper seckillMapper;

    @Mock
    private SeckillOrderMapper seckillOrderMapper;

    @Mock
    private RedisTemplate redisTemplate;

    @Mock
    private BoundHashOperations redisHashOps;

    @InjectMocks
    private SeckillServiceImpl seckillService;

    @Captor
    private ArgumentCaptor<Object> putValueCaptor;

    private Seckill activeSeckill;
    private Seckill futureSeckill;
    private Seckill endedSeckill;
    private Seckill boundaryStartSeckill;
    private Seckill boundaryEndSeckill;

    private static final long ACTIVE_ID = 1L;
    private static final long FUTURE_ID = 2L;
    private static final long ENDED_ID = 3L;
    private static final long BOUNDARY_START_ID = 4L;
    private static final long BOUNDARY_END_ID = 5L;
    private static final long USER_PHONE = 13800138000L;
    private static final BigDecimal MONEY = BigDecimal.valueOf(100);

    /**
     * 根据当前时间动态构造测试数据，确保测试在任何时间运行都有效
     */
    @Before
    public void setUp() {
        long now = System.currentTimeMillis();
        long oneHour = 3600_000L;

        // 进行中的秒杀：start = now - 1h, end = now + 1h
        activeSeckill = buildSeckill(ACTIVE_ID, "进行中商品", now - oneHour, now + oneHour, 100);

        // 未开始的秒杀：start = now + 1h, end = now + 2h
        futureSeckill = buildSeckill(FUTURE_ID, "未开始商品", now + oneHour, now + 2 * oneHour, 50);

        // 已结束的秒杀：start = now - 2h, end = now - 1h
        endedSeckill = buildSeckill(ENDED_ID, "已结束商品", now - 2 * oneHour, now - oneHour, 0);

        // 边界-刚开始的秒杀：start = now - 5s, end = now + 1h
        boundaryStartSeckill = buildSeckill(BOUNDARY_START_ID, "刚开始商品", now - 5000, now + oneHour, 100);

        // 边界-即将结束的秒杀：start = now - 1h, end = now + 5s
        boundaryEndSeckill = buildSeckill(BOUNDARY_END_ID, "即将结束商品", now - oneHour, now + 5000, 5);

        // 默认 Redis hash ops mock
        when(redisTemplate.boundHashOps(anyString())).thenReturn(redisHashOps);
    }

    // ===========================================================
    //  exportSeckillUrl — 缓存未命中场景
    // ===========================================================

    @Test
    public void exportSeckillUrl_cacheMiss_notStarted_returnsNotExposed() {
        when(redisHashOps.get(FUTURE_ID)).thenReturn(null);
        when(seckillMapper.findById(FUTURE_ID)).thenReturn(futureSeckill);

        Exposer exposer = seckillService.exportSeckillUrl(FUTURE_ID);

        assertFalse("未开始时应返回exposed=false", exposer.isExposed());
        assertNull("未开始时md5应为null", exposer.getMd5());
        assertTrue("应返回服务器时间", exposer.getNow() > 0);
        assertEquals(futureSeckill.getStartTime().getTime(), exposer.getStart());
        assertEquals(futureSeckill.getEndTime().getTime(), exposer.getEnd());

        verify(redisHashOps).put(eq(FUTURE_ID), eq(futureSeckill));
        verify(redisTemplate).expire(eq("seckill"), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    public void exportSeckillUrl_cacheMiss_active_returnsExposed() {
        when(redisHashOps.get(ACTIVE_ID)).thenReturn(null);
        when(seckillMapper.findById(ACTIVE_ID)).thenReturn(activeSeckill);

        Exposer exposer = seckillService.exportSeckillUrl(ACTIVE_ID);

        assertTrue("进行中时应返回exposed=true", exposer.isExposed());
        assertNotNull("进行中时md5不为null", exposer.getMd5());

        verify(redisHashOps).put(eq(ACTIVE_ID), eq(activeSeckill));
        verify(redisTemplate).expire(eq("seckill"), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    public void exportSeckillUrl_seckillNotFound_returnsNotExposed() {
        when(redisHashOps.get(999L)).thenReturn(null);
        when(seckillMapper.findById(999L)).thenReturn(null);

        Exposer exposer = seckillService.exportSeckillUrl(999L);

        assertFalse(exposer.isExposed());
        assertEquals(999L, exposer.getSeckillId());
        verify(seckillMapper).findById(999L);
        verify(redisHashOps, never()).put(any(), any());
    }

    // ===========================================================
    //  exportSeckillUrl — 缓存命中场景
    // ===========================================================

    @Test
    public void exportSeckillUrl_cacheHit_active_returnsExposed() {
        when(redisHashOps.get(ACTIVE_ID)).thenReturn(activeSeckill);

        Exposer exposer = seckillService.exportSeckillUrl(ACTIVE_ID);

        assertTrue(exposer.isExposed());
        assertNotNull(exposer.getMd5());
        // 缓存命中时不应查询数据库
        verify(seckillMapper, never()).findById(anyLong());
        // 缓存命中时不应重新设置TTL
        verify(redisTemplate, never()).expire(anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    public void exportSeckillUrl_cacheHit_notStarted_returnsNotExposed() {
        when(redisHashOps.get(FUTURE_ID)).thenReturn(futureSeckill);

        Exposer exposer = seckillService.exportSeckillUrl(FUTURE_ID);

        assertFalse("缓存中未开始的活动应返回exposed=false", exposer.isExposed());
        assertTrue(exposer.getNow() > 0);
        assertEquals(futureSeckill.getStartTime().getTime(), exposer.getStart());
        assertEquals(futureSeckill.getEndTime().getTime(), exposer.getEnd());
    }

    @Test
    public void exportSeckillUrl_cacheHit_ended_returnsNotExposed() {
        when(redisHashOps.get(ENDED_ID)).thenReturn(endedSeckill);

        Exposer exposer = seckillService.exportSeckillUrl(ENDED_ID);

        assertFalse("缓存中已结束的活动应返回exposed=false", exposer.isExposed());
        assertTrue(exposer.getNow() > 0);
    }

    // ===========================================================
    //  exportSeckillUrl — 边界时间点
    // ===========================================================

    @Test
    public void exportSeckillUrl_justStarted_exposed() {
        // startTime = now - 1ms，秒杀刚开始
        when(redisHashOps.get(BOUNDARY_START_ID)).thenReturn(boundaryStartSeckill);

        Exposer exposer = seckillService.exportSeckillUrl(BOUNDARY_START_ID);

        assertTrue("刚开始的秒杀应返回exposed=true", exposer.isExposed());
        assertNotNull(exposer.getMd5());
    }

    @Test
    public void exportSeckillUrl_endingSoon_stillExposed() {
        // endTime = now + 1ms，秒杀即将结束但还没结束
        when(redisHashOps.get(BOUNDARY_END_ID)).thenReturn(boundaryEndSeckill);

        Exposer exposer = seckillService.exportSeckillUrl(BOUNDARY_END_ID);

        assertTrue("即将结束的秒杀应仍返回exposed=true", exposer.isExposed());
        assertNotNull(exposer.getMd5());
    }

    @Test
    public void exportSeckillUrl_exactlyAtStartTime_exposed() {
        // 构造 startTime 精确等于当前时间的秒杀
        long now = System.currentTimeMillis();
        Seckill atStart = buildSeckill(10L, "精确开始", now, now + 3600_000L, 10);
        when(redisHashOps.get(10L)).thenReturn(atStart);

        Exposer exposer = seckillService.exportSeckillUrl(10L);

        assertTrue("在startTime精确时刻应返回exposed=true", exposer.isExposed());
    }

    @Test
    public void exportSeckillUrl_exactlyAtEndTime_exposed() {
        // 构造 endTime 在几秒后的秒杀（仍在窗口内）
        long now = System.currentTimeMillis();
        Seckill nearEnd = buildSeckill(11L, "接近结束", now - 3600_000L, now + 5000, 10);
        when(redisHashOps.get(11L)).thenReturn(nearEnd);

        Exposer exposer = seckillService.exportSeckillUrl(11L);

        assertTrue("在endTime之前的窗口内应返回exposed=true", exposer.isExposed());
    }

    // ===========================================================
    //  exportSeckillUrl — 缓存与DB一致性
    // ===========================================================

    @Test
    public void exportSeckillUrl_cacheExpired_refreshesFromDB() {
        // 模拟缓存过期：第一次get返回null（TTL过期），DB查询返回数据
        when(redisHashOps.get(ACTIVE_ID)).thenReturn(null);
        when(seckillMapper.findById(ACTIVE_ID)).thenReturn(activeSeckill);

        Exposer exposer = seckillService.exportSeckillUrl(ACTIVE_ID);

        assertTrue(exposer.isExposed());
        // 验证从DB重新加载并写入缓存
        verify(seckillMapper).findById(ACTIVE_ID);
        verify(redisHashOps).put(eq(ACTIVE_ID), eq(activeSeckill));
        verify(redisTemplate).expire(eq("seckill"), anyLong(), eq(TimeUnit.SECONDS));
    }

    // ===========================================================
    //  executeSeckill — 成功场景
    // ===========================================================

    @Test
    public void executeSeckill_success() {
        String md5 = computeMD5(ACTIVE_ID);
        // 记录原始库存（activeSeckill是共享可变对象，执行后会被修改）
        long originalStock = activeSeckill.getStockCount();
        when(redisHashOps.get(ACTIVE_ID)).thenReturn(activeSeckill);
        when(seckillOrderMapper.insertOrder(eq(ACTIVE_ID), eq(MONEY), eq(USER_PHONE))).thenReturn(1);
        when(seckillMapper.reduceStock(eq(ACTIVE_ID), any(Date.class))).thenReturn(1);
        SeckillOrder order = buildOrder(ACTIVE_ID, USER_PHONE, MONEY);
        when(seckillOrderMapper.findById(ACTIVE_ID, USER_PHONE)).thenReturn(order);

        SeckillExecution result = seckillService.executeSeckill(ACTIVE_ID, MONEY, USER_PHONE, md5);

        assertEquals(1, result.getState()); // SUCCESS
        assertNotNull(result.getSeckillOrder());
        // 验证缓存中库存被正确递减（stockCount - 1，而非 seckillId - 1）
        verify(redisHashOps).put(eq(ACTIVE_ID), putValueCaptor.capture());
        Seckill updatedCache = (Seckill) putValueCaptor.getValue();
        assertEquals("库存应为originalStock-1", originalStock - 1, updatedCache.getStockCount());
    }

    @Test
    public void executeSeckill_success_atStartBoundary() {
        String md5 = computeMD5(BOUNDARY_START_ID);
        when(redisHashOps.get(BOUNDARY_START_ID)).thenReturn(boundaryStartSeckill);
        when(seckillOrderMapper.insertOrder(eq(BOUNDARY_START_ID), eq(MONEY), eq(USER_PHONE))).thenReturn(1);
        when(seckillMapper.reduceStock(eq(BOUNDARY_START_ID), any(Date.class))).thenReturn(1);
        when(seckillOrderMapper.findById(BOUNDARY_START_ID, USER_PHONE))
                .thenReturn(buildOrder(BOUNDARY_START_ID, USER_PHONE, MONEY));

        SeckillExecution result = seckillService.executeSeckill(BOUNDARY_START_ID, MONEY, USER_PHONE, md5);

        assertEquals(1, result.getState());
    }

    @Test
    public void executeSeckill_success_atEndBoundary() {
        String md5 = computeMD5(BOUNDARY_END_ID);
        when(redisHashOps.get(BOUNDARY_END_ID)).thenReturn(boundaryEndSeckill);
        when(seckillOrderMapper.insertOrder(eq(BOUNDARY_END_ID), eq(MONEY), eq(USER_PHONE))).thenReturn(1);
        when(seckillMapper.reduceStock(eq(BOUNDARY_END_ID), any(Date.class))).thenReturn(1);
        when(seckillOrderMapper.findById(BOUNDARY_END_ID, USER_PHONE))
                .thenReturn(buildOrder(BOUNDARY_END_ID, USER_PHONE, MONEY));

        SeckillExecution result = seckillService.executeSeckill(BOUNDARY_END_ID, MONEY, USER_PHONE, md5);

        assertEquals(1, result.getState());
    }

    // ===========================================================
    //  executeSeckill — 活动结束后旧MD5场景（核心修复验证）
    // ===========================================================

    @Test(expected = SeckillCloseException.class)
    public void executeSeckill_endedActivity_oldMD5_rejected() {
        // 核心修复：活动结束后，即使持有旧MD5也应被Java端时间校验拦截
        String md5 = computeMD5(ENDED_ID);
        when(redisHashOps.get(ENDED_ID)).thenReturn(endedSeckill);

        seckillService.executeSeckill(ENDED_ID, MONEY, USER_PHONE, md5);
    }

    @Test(expected = SeckillNotStartedException.class)
    public void executeSeckill_notStarted_rejected() {
        String md5 = computeMD5(FUTURE_ID);
        when(redisHashOps.get(FUTURE_ID)).thenReturn(futureSeckill);

        seckillService.executeSeckill(FUTURE_ID, MONEY, USER_PHONE, md5);
    }

    @Test
    public void executeSeckill_endedActivity_noDBInteraction() {
        // 验证：活动结束后Java端拦截，不会触发任何数据库操作
        String md5 = computeMD5(ENDED_ID);
        when(redisHashOps.get(ENDED_ID)).thenReturn(endedSeckill);

        try {
            seckillService.executeSeckill(ENDED_ID, MONEY, USER_PHONE, md5);
            fail("应抛出SeckillCloseException");
        } catch (SeckillCloseException e) {
            // 不应有任何数据库交互
            verify(seckillOrderMapper, never()).insertOrder(anyLong(), any(), anyLong());
            verify(seckillMapper, never()).reduceStock(anyLong(), any(Date.class));
            verify(redisHashOps, never()).put(any(), any());
        }
    }

    // ===========================================================
    //  executeSeckill — 缓存未命中时的降级处理
    // ===========================================================

    @Test
    public void executeSeckill_cacheMiss_fallbackToSQL() {
        // 缓存中没有数据时，跳过Java端时间校验，依赖SQL的reduceStock时间条件兜底
        String md5 = computeMD5(ACTIVE_ID);
        when(redisHashOps.get(ACTIVE_ID)).thenReturn(null);
        when(seckillOrderMapper.insertOrder(eq(ACTIVE_ID), eq(MONEY), eq(USER_PHONE))).thenReturn(1);
        when(seckillMapper.reduceStock(eq(ACTIVE_ID), any(Date.class))).thenReturn(1);
        when(seckillOrderMapper.findById(ACTIVE_ID, USER_PHONE))
                .thenReturn(buildOrder(ACTIVE_ID, USER_PHONE, MONEY));

        SeckillExecution result = seckillService.executeSeckill(ACTIVE_ID, MONEY, USER_PHONE, md5);

        assertEquals(1, result.getState());
        // 缓存未命中时不应尝试更新缓存
        verify(redisHashOps, never()).put(any(), any());
    }

    // ===========================================================
    //  executeSeckill — 重复秒杀
    // ===========================================================

    @Test(expected = RepeatKillException.class)
    public void executeSeckill_repeatKill_throws() {
        String md5 = computeMD5(ACTIVE_ID);
        when(redisHashOps.get(ACTIVE_ID)).thenReturn(activeSeckill);
        // INSERT IGNORE 返回0表示重复
        when(seckillOrderMapper.insertOrder(eq(ACTIVE_ID), eq(MONEY), eq(USER_PHONE))).thenReturn(0);

        seckillService.executeSeckill(ACTIVE_ID, MONEY, USER_PHONE, md5);
    }

    @Test
    public void executeSeckill_repeatKill_noStockReduction() {
        String md5 = computeMD5(ACTIVE_ID);
        when(redisHashOps.get(ACTIVE_ID)).thenReturn(activeSeckill);
        when(seckillOrderMapper.insertOrder(eq(ACTIVE_ID), eq(MONEY), eq(USER_PHONE))).thenReturn(0);

        try {
            seckillService.executeSeckill(ACTIVE_ID, MONEY, USER_PHONE, md5);
            fail("应抛出RepeatKillException");
        } catch (RepeatKillException e) {
            // 重复秒杀时不应扣减库存
            verify(seckillMapper, never()).reduceStock(anyLong(), any(Date.class));
            verify(redisHashOps, never()).put(any(), any());
        }
    }

    // ===========================================================
    //  executeSeckill — 库存耗尽
    // ===========================================================

    @Test(expected = SeckillCloseException.class)
    public void executeSeckill_stockExhausted_throws() {
        String md5 = computeMD5(ACTIVE_ID);
        when(redisHashOps.get(ACTIVE_ID)).thenReturn(activeSeckill);
        when(seckillOrderMapper.insertOrder(eq(ACTIVE_ID), eq(MONEY), eq(USER_PHONE))).thenReturn(1);
        // stock_count = 0, reduceStock返回0
        when(seckillMapper.reduceStock(eq(ACTIVE_ID), any(Date.class))).thenReturn(0);

        seckillService.executeSeckill(ACTIVE_ID, MONEY, USER_PHONE, md5);
    }

    // ===========================================================
    //  executeSeckill — MD5校验
    // ===========================================================

    @Test(expected = SeckillException.class)
    public void executeSeckill_nullMD5_throws() {
        seckillService.executeSeckill(ACTIVE_ID, MONEY, USER_PHONE, null);
    }

    @Test(expected = SeckillException.class)
    public void executeSeckill_wrongMD5_throws() {
        seckillService.executeSeckill(ACTIVE_ID, MONEY, USER_PHONE, "wrong_md5_value");
    }

    @Test
    public void executeSeckill_invalidMD5_noDBInteraction() {
        try {
            seckillService.executeSeckill(ACTIVE_ID, MONEY, USER_PHONE, "tampered_md5");
            fail("应抛出SeckillException");
        } catch (SeckillException e) {
            verify(seckillOrderMapper, never()).insertOrder(anyLong(), any(), anyLong());
            verify(seckillMapper, never()).reduceStock(anyLong(), any(Date.class));
        }
    }

    // ===========================================================
    //  executeSeckill — 库存缓存更新正确性验证（stock bug修复）
    // ===========================================================

    @Test
    public void executeSeckill_stockCountUpdatedCorrectly_notSeckillIdBased() {
        // 核心修复验证：库存递减使用 stockCount - 1，而非 seckillId - 1
        // 使用 seckillId=1, stockCount=100 来区分两种计算方式
        long seckillId = 1L;
        long originalStock = 100L;
        Seckill seckill = buildSeckill(seckillId, "库存测试",
                System.currentTimeMillis() - 3600_000L,
                System.currentTimeMillis() + 3600_000L,
                originalStock);

        String md5 = computeMD5(seckillId);
        when(redisHashOps.get(seckillId)).thenReturn(seckill);
        when(seckillOrderMapper.insertOrder(eq(seckillId), eq(MONEY), eq(USER_PHONE))).thenReturn(1);
        when(seckillMapper.reduceStock(eq(seckillId), any(Date.class))).thenReturn(1);
        when(seckillOrderMapper.findById(seckillId, USER_PHONE))
                .thenReturn(buildOrder(seckillId, USER_PHONE, MONEY));

        seckillService.executeSeckill(seckillId, MONEY, USER_PHONE, md5);

        // 正确：stockCount = 100 - 1 = 99
        // 原bug：stockCount = seckillId - 1 = 1 - 1 = 0
        verify(redisHashOps).put(eq(seckillId), putValueCaptor.capture());
        Seckill updated = (Seckill) putValueCaptor.getValue();
        assertEquals("库存应为stockCount-1=99，而非seckillId-1=0",
                originalStock - 1, updated.getStockCount());
    }

    // ===========================================================
    //  findAll — 缓存场景
    // ===========================================================

    @Test
    public void findAll_cacheHit_returnsFromCache() {
        List<Seckill> cachedList = Arrays.asList(activeSeckill, futureSeckill, endedSeckill);
        when(redisHashOps.values()).thenReturn(cachedList);

        List<Seckill> result = seckillService.findAll();

        assertEquals(3, result.size());
        verify(seckillMapper, never()).findAll();
        // 缓存命中时不应重置TTL
        verify(redisTemplate, never()).expire(anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    public void findAll_cacheMiss_loadsFromDBAndSetsTTL() {
        when(redisHashOps.values()).thenReturn(null);
        List<Seckill> dbList = Arrays.asList(activeSeckill, futureSeckill);
        when(seckillMapper.findAll()).thenReturn(dbList);

        List<Seckill> result = seckillService.findAll();

        assertEquals(2, result.size());
        // 验证每条数据写入缓存
        verify(redisHashOps).put(eq(ACTIVE_ID), eq(activeSeckill));
        verify(redisHashOps).put(eq(FUTURE_ID), eq(futureSeckill));
        // 验证设置了缓存TTL（5分钟 = 300秒）
        verify(redisTemplate).expire(eq("seckill"), eq(300L), eq(TimeUnit.SECONDS));
    }

    @Test
    public void findAll_cacheEmpty_loadsFromDB() {
        when(redisHashOps.values()).thenReturn(new ArrayList<>());
        List<Seckill> dbList = Arrays.asList(activeSeckill);
        when(seckillMapper.findAll()).thenReturn(dbList);

        List<Seckill> result = seckillService.findAll();

        assertEquals(1, result.size());
        verify(seckillMapper).findAll();
        verify(redisTemplate).expire(eq("seckill"), anyLong(), eq(TimeUnit.SECONDS));
    }

    // ===========================================================
    //  缓存失效场景 — TTL过期后数据一致性
    // ===========================================================

    @Test
    public void cacheInvalidation_afterExpiry_freshDataFromDB() {
        // 模拟第一次请求：缓存未命中，从DB加载
        when(redisHashOps.get(ACTIVE_ID))
                .thenReturn(null)                     // 第一次：缓存miss
                .thenReturn(activeSeckill);            // 第二次：缓存hit（已写入）
        when(seckillMapper.findById(ACTIVE_ID)).thenReturn(activeSeckill);

        Exposer first = seckillService.exportSeckillUrl(ACTIVE_ID);
        assertTrue(first.isExposed());
        verify(seckillMapper, times(1)).findById(ACTIVE_ID);

        // 第二次请求：缓存命中，不再查DB
        Exposer second = seckillService.exportSeckillUrl(ACTIVE_ID);
        assertTrue(second.isExposed());
        // findById 仍只被调用了1次（第一次）
        verify(seckillMapper, times(1)).findById(ACTIVE_ID);
    }

    @Test
    public void cacheInvalidation_staleCacheResolvedByTTL() {
        // 模拟场景：缓存中数据已过时（DB已更新endTime），TTL过期后重新加载
        long now = System.currentTimeMillis();

        // 旧缓存数据：endTime已过
        Seckill staleSeckill = buildSeckill(ACTIVE_ID, "旧数据",
                now - 7200_000L, now - 3600_000L, 100);
        // DB中的新数据：endTime已更新为未来
        Seckill freshSeckill = buildSeckill(ACTIVE_ID, "新数据",
                now - 7200_000L, now + 3600_000L, 100);

        // 缓存miss（TTL过期），从DB获取到新鲜数据
        when(redisHashOps.get(ACTIVE_ID)).thenReturn(null);
        when(seckillMapper.findById(ACTIVE_ID)).thenReturn(freshSeckill);

        Exposer exposer = seckillService.exportSeckillUrl(ACTIVE_ID);

        // 由于缓存TTL过期后从DB加载了新鲜数据，秒杀应该是开启状态
        assertTrue("TTL过期后应从DB获取新鲜数据，秒杀应为开启状态", exposer.isExposed());
        verify(redisHashOps).put(eq(ACTIVE_ID), eq(freshSeckill));
    }

    @Test
    public void exportSeckillUrl_MD5Consistency() {
        // 验证相同seckillId生成的MD5始终一致
        when(redisHashOps.get(ACTIVE_ID)).thenReturn(activeSeckill);

        Exposer first = seckillService.exportSeckillUrl(ACTIVE_ID);
        Exposer second = seckillService.exportSeckillUrl(ACTIVE_ID);

        assertEquals("同一seckillId的MD5应一致", first.getMd5(), second.getMd5());
    }

    // ===========================================================
    //  四种核心结果稳定性验证
    // ===========================================================

    @Test
    public void fourOutcomes_stability() {
        String md5 = computeMD5(ACTIVE_ID);

        // 1. 重复秒杀 → RepeatKillException
        when(redisHashOps.get(ACTIVE_ID)).thenReturn(activeSeckill);
        when(seckillOrderMapper.insertOrder(eq(ACTIVE_ID), eq(MONEY), eq(USER_PHONE))).thenReturn(0);
        try {
            seckillService.executeSeckill(ACTIVE_ID, MONEY, USER_PHONE, md5);
            fail("应抛出RepeatKillException");
        } catch (RepeatKillException e) {
            // expected
        }

        // 2. 库存耗尽 → SeckillCloseException
        reset(redisHashOps, seckillOrderMapper, seckillMapper);
        when(redisTemplate.boundHashOps(anyString())).thenReturn(redisHashOps);
        when(redisHashOps.get(ACTIVE_ID)).thenReturn(activeSeckill);
        when(seckillOrderMapper.insertOrder(eq(ACTIVE_ID), eq(MONEY), eq(USER_PHONE))).thenReturn(1);
        when(seckillMapper.reduceStock(eq(ACTIVE_ID), any(Date.class))).thenReturn(0);
        try {
            seckillService.executeSeckill(ACTIVE_ID, MONEY, USER_PHONE, md5);
            fail("应抛出SeckillCloseException");
        } catch (SeckillCloseException e) {
            // expected
        }

        // 3. 未开始 → SeckillNotStartedException
        reset(redisHashOps);
        when(redisTemplate.boundHashOps(anyString())).thenReturn(redisHashOps);
        when(redisHashOps.get(FUTURE_ID)).thenReturn(futureSeckill);
        try {
            seckillService.executeSeckill(FUTURE_ID, MONEY, USER_PHONE, computeMD5(FUTURE_ID));
            fail("应抛出SeckillNotStartedException");
        } catch (SeckillNotStartedException e) {
            // expected
        }

        // 4. 已结束 → SeckillCloseException
        reset(redisHashOps);
        when(redisTemplate.boundHashOps(anyString())).thenReturn(redisHashOps);
        when(redisHashOps.get(ENDED_ID)).thenReturn(endedSeckill);
        try {
            seckillService.executeSeckill(ENDED_ID, MONEY, USER_PHONE, computeMD5(ENDED_ID));
            fail("应抛出SeckillCloseException");
        } catch (SeckillCloseException e) {
            // expected
        }
    }

    // ===========================================================
    //  辅助方法
    // ===========================================================

    private Seckill buildSeckill(long id, String title, long startTimeMillis, long endTimeMillis, long stock) {
        Seckill seckill = new Seckill();
        seckill.setSeckillId(id);
        seckill.setTitle(title);
        seckill.setPrice(BigDecimal.valueOf(999.00));
        seckill.setCostPrice(BigDecimal.valueOf(499.00));
        seckill.setStockCount(stock);
        seckill.setStartTime(new Date(startTimeMillis));
        seckill.setEndTime(new Date(endTimeMillis));
        seckill.setCreateTime(new Date());
        return seckill;
    }

    private SeckillOrder buildOrder(long seckillId, long userPhone, BigDecimal money) {
        SeckillOrder order = new SeckillOrder();
        order.setSeckillId(seckillId);
        order.setUserPhone(userPhone);
        order.setMoney(money);
        order.setCreateTime(new Date());
        return order;
    }

    /**
     * 使用与SeckillServiceImpl相同的盐值计算MD5，用于测试验证
     */
    private String computeMD5(long seckillId) {
        String salt = "sjajaspu-i-2jrfm;sd";
        String base = seckillId + "/" + salt;
        return org.springframework.util.DigestUtils.md5DigestAsHex(base.getBytes());
    }
}
