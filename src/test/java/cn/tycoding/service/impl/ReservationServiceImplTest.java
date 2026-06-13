package cn.tycoding.service.impl;

import cn.tycoding.entity.Seckill;
import cn.tycoding.entity.SeckillReservation;
import cn.tycoding.mapper.ReservationMapper;
import cn.tycoding.mapper.SeckillMapper;
import cn.tycoding.service.ReservationService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ReservationService 回归测试
 *
 * 覆盖场景：
 * 1. 预约重复提交
 * 2. 活动未开始（对拉取提醒的校验）
 * 3. 活动已结束（对预约的校验）
 * 4. 提醒消费幂等（pullReminders第二次调用返回空）
 * 5. 正常预约 / 正常取消 / 正常查询
 * 6. 活动结束后预约自动失效（getUserReservations过滤）
 *
 * @auther TyCoding
 * @date 2018/10/8
 */
@RunWith(MockitoJUnitRunner.class)
public class ReservationServiceImplTest {

    @Mock
    private SeckillMapper seckillMapper;

    @Mock
    private ReservationMapper reservationMapper;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private SetOperations<String, Object> setOperations;

    @InjectMocks
    private ReservationServiceImpl reservationService;

    //测试用的秒杀商品：尚未开始（用于预约测试）
    private Seckill futureSeckill;
    //测试用的秒杀商品：已结束（用于活动已结束测试）
    private Seckill endedSeckill;
    //测试用的秒杀商品：进行中（用于拉取提醒测试）
    private Seckill activeSeckill;

    private static final long FUTURE_SECKILL_ID = 100L;
    private static final long ENDED_SECKILL_ID = 200L;
    private static final long ACTIVE_SECKILL_ID = 300L;
    private static final long USER_PHONE = 13800138000L;
    private static final long USER_PHONE_2 = 13900139000L;

    @Before
    public void setUp() {
        long now = System.currentTimeMillis();
        long oneHour = 3600 * 1000L;

        //尚未开始的秒杀活动：start = now + 1h, end = now + 2h
        futureSeckill = buildSeckill(FUTURE_SECKILL_ID, "未开始商品",
                now + oneHour, now + oneHour * 2);

        //已结束的秒杀活动：start = now - 2h, end = now - 1h
        endedSeckill = buildSeckill(ENDED_SECKILL_ID, "已结束商品",
                now - oneHour * 2, now - oneHour);

        //进行中的秒杀活动：start = now - 1h, end = now + 1h
        activeSeckill = buildSeckill(ACTIVE_SECKILL_ID, "进行中商品",
                now - oneHour, now + oneHour);

        //配置RedisTemplate的opsForSet()返回mock
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
    }

    // ============================
    // 1. 预约重复提交测试
    // ============================

    @Test
    public void testReserve_duplicateReservation_RedisHit() {
        //Redis中已存在该用户的预约记录
        when(seckillMapper.findById(FUTURE_SECKILL_ID)).thenReturn(futureSeckill);
        when(setOperations.isMember(anyString(), eq(USER_PHONE))).thenReturn(true);

        try {
            reservationService.reserve(FUTURE_SECKILL_ID, USER_PHONE);
            fail("应抛出重复预约异常");
        } catch (RuntimeException e) {
            assertEquals("请勿重复预约", e.getMessage());
        }

        //验证没有调用数据库插入
        verify(reservationMapper, never()).insertReservation(anyLong(), anyLong());
    }

    @Test
    public void testReserve_DuplicateReservation_DbConflict() {
        //Redis中不存在，但数据库唯一索引冲突（INSERT IGNORE返回0）
        when(seckillMapper.findById(FUTURE_SECKILL_ID)).thenReturn(futureSeckill);
        when(setOperations.isMember(anyString(), eq(USER_PHONE))).thenReturn(false);
        when(reservationMapper.insertReservation(FUTURE_SECKILL_ID, USER_PHONE)).thenReturn(0);

        try {
            reservationService.reserve(FUTURE_SECKILL_ID, USER_PHONE);
            fail("应抛出重复预约异常");
        } catch (RuntimeException e) {
            assertEquals("请勿重复预约", e.getMessage());
        }

        //验证Redis缓存被同步写入
        verify(setOperations).add(anyString(), eq(USER_PHONE));
    }

    // ============================
    // 2. 活动未开始测试（拉取提醒时的校验）
    // ============================

    @Test
    public void testPullReminders_ActivityNotStarted() {
        when(seckillMapper.findById(FUTURE_SECKILL_ID)).thenReturn(futureSeckill);

        try {
            reservationService.pullReminders(FUTURE_SECKILL_ID);
            fail("应抛出活动尚未开始异常");
        } catch (RuntimeException e) {
            assertEquals("活动尚未开始", e.getMessage());
        }

        //验证没有查询预约记录
        verify(reservationMapper, never()).findPendingBySeckillId(anyLong());
    }

    // ============================
    // 3. 活动已结束测试
    // ============================

    @Test
    public void testReserve_ActivityEnded() {
        when(seckillMapper.findById(ENDED_SECKILL_ID)).thenReturn(endedSeckill);

        try {
            reservationService.reserve(ENDED_SECKILL_ID, USER_PHONE);
            fail("应抛出活动已结束异常");
        } catch (RuntimeException e) {
            assertEquals("活动已结束", e.getMessage());
        }

        //验证没有调用Redis和数据库
        verify(setOperations, never()).isMember(anyString(), any());
        verify(reservationMapper, never()).insertReservation(anyLong(), anyLong());
    }

    @Test
    public void testPullReminders_ActivityEnded() {
        when(seckillMapper.findById(ENDED_SECKILL_ID)).thenReturn(endedSeckill);

        try {
            reservationService.pullReminders(ENDED_SECKILL_ID);
            fail("应抛出活动已结束异常");
        } catch (RuntimeException e) {
            assertEquals("活动已结束", e.getMessage());
        }
    }

    @Test
    public void testCancelReservation_ActivityEnded() {
        when(seckillMapper.findById(ENDED_SECKILL_ID)).thenReturn(endedSeckill);

        try {
            reservationService.cancelReservation(ENDED_SECKILL_ID, USER_PHONE);
            fail("应抛出活动已结束异常");
        } catch (RuntimeException e) {
            assertEquals("活动已结束", e.getMessage());
        }
    }

    // ============================
    // 4. 提醒消费幂等测试
    // ============================

    @Test
    public void testPullReminders_IdempotentConsumption_FirstPull() {
        //第一次拉取：有待提醒记录
        when(seckillMapper.findById(ACTIVE_SECKILL_ID)).thenReturn(activeSeckill);

        SeckillReservation r1 = buildReservation(1L, ACTIVE_SECKILL_ID, USER_PHONE, 0);
        SeckillReservation r2 = buildReservation(2L, ACTIVE_SECKILL_ID, USER_PHONE_2, 0);
        List<SeckillReservation> pendingList = new ArrayList<>(Arrays.asList(r1, r2));

        when(reservationMapper.findPendingBySeckillId(ACTIVE_SECKILL_ID)).thenReturn(pendingList);
        when(reservationMapper.markConsumedBySeckillId(ACTIVE_SECKILL_ID)).thenReturn(2);

        List<SeckillReservation> result = reservationService.pullReminders(ACTIVE_SECKILL_ID);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals(USER_PHONE, result.get(0).getUserPhone());
        assertEquals(USER_PHONE_2, result.get(1).getUserPhone());

        //验证标记操作被调用
        verify(reservationMapper).markConsumedBySeckillId(ACTIVE_SECKILL_ID);
    }

    @Test
    public void testPullReminders_IdempotentConsumption_SecondPull() {
        //第二次拉取：所有记录已被标记为已提醒，返回空
        when(seckillMapper.findById(ACTIVE_SECKILL_ID)).thenReturn(activeSeckill);
        when(reservationMapper.findPendingBySeckillId(ACTIVE_SECKILL_ID))
                .thenReturn(new ArrayList<>());

        List<SeckillReservation> result = reservationService.pullReminders(ACTIVE_SECKILL_ID);

        assertNotNull(result);
        assertTrue("第二次拉取应返回空列表（幂等消费）", result.isEmpty());

        //验证标记操作未被调用（因为没有待提醒记录）
        verify(reservationMapper, never()).markConsumedBySeckillId(anyLong());
    }

    // ============================
    // 5. 正常流程测试
    // ============================

    @Test
    public void testReserve_Success() {
        when(seckillMapper.findById(FUTURE_SECKILL_ID)).thenReturn(futureSeckill);
        when(setOperations.isMember(anyString(), eq(USER_PHONE))).thenReturn(false);
        when(reservationMapper.insertReservation(FUTURE_SECKILL_ID, USER_PHONE)).thenReturn(1);

        SeckillReservation expectedReservation = buildReservation(1L, FUTURE_SECKILL_ID, USER_PHONE, 0);
        when(reservationMapper.findBySeckillIdAndUserPhone(FUTURE_SECKILL_ID, USER_PHONE))
                .thenReturn(expectedReservation);

        SeckillReservation result = reservationService.reserve(FUTURE_SECKILL_ID, USER_PHONE);

        assertNotNull(result);
        assertEquals(FUTURE_SECKILL_ID, result.getSeckillId());
        assertEquals(USER_PHONE, result.getUserPhone());

        //验证Redis写入
        verify(setOperations).add(anyString(), eq(USER_PHONE));
        //验证数据库写入
        verify(reservationMapper).insertReservation(FUTURE_SECKILL_ID, USER_PHONE);
    }

    @Test
    public void testReserve_ActivityAlreadyStarted() {
        //活动已开始（进行中），不需要预约
        when(seckillMapper.findById(ACTIVE_SECKILL_ID)).thenReturn(activeSeckill);

        try {
            reservationService.reserve(ACTIVE_SECKILL_ID, USER_PHONE);
            fail("应抛出活动已开始异常");
        } catch (RuntimeException e) {
            assertEquals("活动已开始，无需预约", e.getMessage());
        }
    }

    @Test
    public void testReserve_SeckillNotFound() {
        when(seckillMapper.findById(999L)).thenReturn(null);

        try {
            reservationService.reserve(999L, USER_PHONE);
            fail("应抛出活动不存在异常");
        } catch (RuntimeException e) {
            assertEquals("秒杀活动不存在", e.getMessage());
        }
    }

    @Test
    public void testCancelReservation_Success() {
        when(seckillMapper.findById(FUTURE_SECKILL_ID)).thenReturn(futureSeckill);
        when(reservationMapper.deleteReservation(FUTURE_SECKILL_ID, USER_PHONE)).thenReturn(1);

        boolean result = reservationService.cancelReservation(FUTURE_SECKILL_ID, USER_PHONE);

        assertTrue(result);
        //验证Redis缓存清除
        verify(setOperations).remove(anyString(), eq(USER_PHONE));
        //验证数据库删除
        verify(reservationMapper).deleteReservation(FUTURE_SECKILL_ID, USER_PHONE);
    }

    @Test
    public void testCancelReservation_NoReservation() {
        when(seckillMapper.findById(FUTURE_SECKILL_ID)).thenReturn(futureSeckill);
        when(reservationMapper.deleteReservation(FUTURE_SECKILL_ID, USER_PHONE)).thenReturn(0);

        boolean result = reservationService.cancelReservation(FUTURE_SECKILL_ID, USER_PHONE);

        assertFalse("没有预约记录时应返回false", result);
    }

    @Test
    public void testGetUserReservations_Success() {
        SeckillReservation r1 = buildReservation(1L, FUTURE_SECKILL_ID, USER_PHONE, 0);
        List<SeckillReservation> reservations = new ArrayList<>(Arrays.asList(r1));
        when(reservationMapper.findByUserPhone(USER_PHONE)).thenReturn(reservations);
        when(seckillMapper.findById(FUTURE_SECKILL_ID)).thenReturn(futureSeckill);

        List<SeckillReservation> result = reservationService.getUserReservations(USER_PHONE);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(FUTURE_SECKILL_ID, result.get(0).getSeckillId());
    }

    @Test
    public void testGetUserReservations_FilterEndedActivity() {
        //用户有一个已结束活动的预约，应被过滤
        SeckillReservation r1 = buildReservation(1L, ENDED_SECKILL_ID, USER_PHONE, 0);
        SeckillReservation r2 = buildReservation(2L, FUTURE_SECKILL_ID, USER_PHONE, 0);
        List<SeckillReservation> reservations = new ArrayList<>(Arrays.asList(r1, r2));
        when(reservationMapper.findByUserPhone(USER_PHONE)).thenReturn(reservations);
        when(seckillMapper.findById(ENDED_SECKILL_ID)).thenReturn(endedSeckill);
        when(seckillMapper.findById(FUTURE_SECKILL_ID)).thenReturn(futureSeckill);

        List<SeckillReservation> result = reservationService.getUserReservations(USER_PHONE);

        assertNotNull(result);
        assertEquals("已结束活动的预约应被过滤", 1, result.size());
        assertEquals(FUTURE_SECKILL_ID, result.get(0).getSeckillId());
    }

    @Test
    public void testGetUserReservations_Empty() {
        when(reservationMapper.findByUserPhone(USER_PHONE)).thenReturn(new ArrayList<>());

        List<SeckillReservation> result = reservationService.getUserReservations(USER_PHONE);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testPullReminders_SeckillNotFound() {
        when(seckillMapper.findById(999L)).thenReturn(null);

        try {
            reservationService.pullReminders(999L);
            fail("应抛出活动不存在异常");
        } catch (RuntimeException e) {
            assertEquals("秒杀活动不存在", e.getMessage());
        }
    }

    // ============================
    // 辅助方法
    // ============================

    private Seckill buildSeckill(long id, String title, long startTimeMillis, long endTimeMillis) {
        Seckill seckill = new Seckill();
        seckill.setSeckillId(id);
        seckill.setTitle(title);
        seckill.setPrice(BigDecimal.valueOf(999.00));
        seckill.setCostPrice(BigDecimal.valueOf(499.00));
        seckill.setStockCount(100);
        seckill.setStartTime(new Date(startTimeMillis));
        seckill.setEndTime(new Date(endTimeMillis));
        seckill.setCreateTime(new Date());
        return seckill;
    }

    private SeckillReservation buildReservation(long id, long seckillId, long userPhone, int status) {
        SeckillReservation r = new SeckillReservation();
        r.setId(id);
        r.setSeckillId(seckillId);
        r.setUserPhone(userPhone);
        r.setStatus(status);
        r.setCreateTime(new Date());
        return r;
    }
}
