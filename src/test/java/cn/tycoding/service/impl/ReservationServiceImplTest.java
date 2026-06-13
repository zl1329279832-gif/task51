package cn.tycoding.service.impl;

import cn.tycoding.entity.Seckill;
import cn.tycoding.entity.SeckillReservation;
import cn.tycoding.mapper.ReservationMapper;
import cn.tycoding.mapper.SeckillMapper;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 预约提醒服务回归测试
 * 覆盖：预约重复提交、活动未开始、活动已结束、提醒消费幂等
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

    private Seckill futureSeckill;   // 未开始的秒杀
    private Seckill activeSeckill;   // 进行中的秒杀
    private Seckill endedSeckill;    // 已结束的秒杀

    private static final long SECKILL_ID = 1L;
    private static final long USER_PHONE = 13712345678L;

    @Before
    public void setUp() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);

        long now = System.currentTimeMillis();

        // 未开始：startTime在1小时后，endTime在2小时后
        futureSeckill = new Seckill();
        futureSeckill.setSeckillId(SECKILL_ID);
        futureSeckill.setStartTime(new Date(now + 3600_000));
        futureSeckill.setEndTime(new Date(now + 7200_000));

        // 进行中：startTime在1小时前，endTime在1小时后
        activeSeckill = new Seckill();
        activeSeckill.setSeckillId(SECKILL_ID);
        activeSeckill.setStartTime(new Date(now - 3600_000));
        activeSeckill.setEndTime(new Date(now + 3600_000));

        // 已结束：startTime在2小时前，endTime在1小时前
        endedSeckill = new Seckill();
        endedSeckill.setSeckillId(SECKILL_ID);
        endedSeckill.setStartTime(new Date(now - 7200_000));
        endedSeckill.setEndTime(new Date(now - 3600_000));
    }

    // ==================== 预约成功 ====================

    @Test
    public void testReserveSuccess() {
        when(seckillMapper.findById(SECKILL_ID)).thenReturn(futureSeckill);
        when(setOperations.isMember(anyString(), eq(USER_PHONE))).thenReturn(false);
        when(reservationMapper.insertReservation(SECKILL_ID, USER_PHONE)).thenReturn(1);

        SeckillReservation expected = new SeckillReservation();
        expected.setSeckillId(SECKILL_ID);
        expected.setUserPhone(USER_PHONE);
        when(reservationMapper.findBySeckillIdAndUserPhone(SECKILL_ID, USER_PHONE)).thenReturn(expected);

        SeckillReservation result = reservationService.reserve(SECKILL_ID, USER_PHONE);

        assertNotNull(result);
        assertEquals(SECKILL_ID, result.getSeckillId());
        assertEquals(USER_PHONE, result.getUserPhone());
        verify(setOperations).add(eq("seckill:reservation:" + SECKILL_ID), eq(USER_PHONE));
    }

    // ==================== 预约重复提交 ====================

    @Test
    public void testReserveDuplicate_RedisHit() {
        // Redis SET中已存在，快速拒绝
        when(seckillMapper.findById(SECKILL_ID)).thenReturn(futureSeckill);
        when(setOperations.isMember(anyString(), eq(USER_PHONE))).thenReturn(true);

        try {
            reservationService.reserve(SECKILL_ID, USER_PHONE);
            fail("应抛出重复预约异常");
        } catch (RuntimeException e) {
            assertEquals("不可重复预约", e.getMessage());
        }
        // 不应该触发数据库写入
        verify(reservationMapper, never()).insertReservation(anyLong(), anyLong());
    }

    @Test
    public void testReserveDuplicate_DbHit() {
        // Redis缓存未命中，但DB唯一键冲突（INSERT IGNORE返回0）
        when(seckillMapper.findById(SECKILL_ID)).thenReturn(futureSeckill);
        when(setOperations.isMember(anyString(), eq(USER_PHONE))).thenReturn(false);
        when(reservationMapper.insertReservation(SECKILL_ID, USER_PHONE)).thenReturn(0);

        try {
            reservationService.reserve(SECKILL_ID, USER_PHONE);
            fail("应抛出重复预约异常");
        } catch (RuntimeException e) {
            assertEquals("不可重复预约", e.getMessage());
        }
        // 应补偿写入Redis SET
        verify(setOperations).add(eq("seckill:reservation:" + SECKILL_ID), eq(USER_PHONE));
    }

    // ==================== 活动已结束 ====================

    @Test
    public void testReserveAfterEnd() {
        when(seckillMapper.findById(SECKILL_ID)).thenReturn(endedSeckill);

        try {
            reservationService.reserve(SECKILL_ID, USER_PHONE);
            fail("应抛出活动已结束异常");
        } catch (RuntimeException e) {
            assertEquals("秒杀活动已结束，无法预约", e.getMessage());
        }
        verify(reservationMapper, never()).insertReservation(anyLong(), anyLong());
    }

    @Test
    public void testCancelAfterEnd() {
        when(seckillMapper.findById(SECKILL_ID)).thenReturn(endedSeckill);

        try {
            reservationService.cancelReservation(SECKILL_ID, USER_PHONE);
            fail("应抛出活动已结束异常");
        } catch (RuntimeException e) {
            assertEquals("秒杀活动已结束", e.getMessage());
        }
        verify(reservationMapper, never()).deleteReservation(anyLong(), anyLong());
    }

    // ==================== 活动已开始（无需预约） ====================

    @Test
    public void testReserveAfterStart() {
        when(seckillMapper.findById(SECKILL_ID)).thenReturn(activeSeckill);

        try {
            reservationService.reserve(SECKILL_ID, USER_PHONE);
            fail("应抛出活动已开始异常");
        } catch (RuntimeException e) {
            assertEquals("秒杀活动已开始，无需预约", e.getMessage());
        }
        verify(reservationMapper, never()).insertReservation(anyLong(), anyLong());
    }

    // ==================== 活动不存在 ====================

    @Test
    public void testReserveNotFound() {
        when(seckillMapper.findById(SECKILL_ID)).thenReturn(null);

        try {
            reservationService.reserve(SECKILL_ID, USER_PHONE);
            fail("应抛出活动不存在异常");
        } catch (RuntimeException e) {
            assertEquals("秒杀活动不存在", e.getMessage());
        }
    }

    // ==================== 取消预约 ====================

    @Test
    public void testCancelSuccess() {
        when(seckillMapper.findById(SECKILL_ID)).thenReturn(futureSeckill);
        when(reservationMapper.deleteReservation(SECKILL_ID, USER_PHONE)).thenReturn(1);

        boolean result = reservationService.cancelReservation(SECKILL_ID, USER_PHONE);

        assertTrue(result);
        verify(setOperations).remove(eq("seckill:reservation:" + SECKILL_ID), eq(USER_PHONE));
    }

    @Test
    public void testCancelNonExistent() {
        // 取消一个不存在的预约（deleteCount=0）
        when(seckillMapper.findById(SECKILL_ID)).thenReturn(futureSeckill);
        when(reservationMapper.deleteReservation(SECKILL_ID, USER_PHONE)).thenReturn(0);

        boolean result = reservationService.cancelReservation(SECKILL_ID, USER_PHONE);

        assertFalse(result);
    }

    // ==================== 查询我的预约（过滤已结束） ====================

    @Test
    public void testGetUserReservations_FilterEnded() {
        SeckillReservation r1 = new SeckillReservation();
        r1.setSeckillId(1L);
        SeckillReservation r2 = new SeckillReservation();
        r2.setSeckillId(2L);

        when(reservationMapper.findByUserPhone(USER_PHONE)).thenReturn(Arrays.asList(r1, r2));
        when(seckillMapper.findById(1L)).thenReturn(futureSeckill);   // 未结束
        when(seckillMapper.findById(2L)).thenReturn(endedSeckill);    // 已结束

        List<SeckillReservation> result = reservationService.getUserReservations(USER_PHONE);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getSeckillId());
    }

    @Test
    public void testGetUserReservations_Empty() {
        when(reservationMapper.findByUserPhone(USER_PHONE)).thenReturn(Collections.emptyList());

        List<SeckillReservation> result = reservationService.getUserReservations(USER_PHONE);

        assertTrue(result.isEmpty());
    }

    // ==================== 拉取提醒 ====================

    @Test
    public void testPullRemindersSuccess() {
        when(seckillMapper.findById(SECKILL_ID)).thenReturn(activeSeckill);

        SeckillReservation r1 = new SeckillReservation();
        r1.setSeckillId(SECKILL_ID);
        r1.setUserPhone(USER_PHONE);
        r1.setStatus(0);
        when(reservationMapper.findPendingBySeckillId(SECKILL_ID)).thenReturn(Arrays.asList(r1));
        when(reservationMapper.markConsumedBySeckillId(SECKILL_ID)).thenReturn(1);

        List<SeckillReservation> result = reservationService.pullReminders(SECKILL_ID);

        assertEquals(1, result.size());
        verify(reservationMapper).markConsumedBySeckillId(SECKILL_ID);
    }

    // ==================== 活动未开始时拉取提醒 ====================

    @Test
    public void testPullRemindersBeforeStart() {
        when(seckillMapper.findById(SECKILL_ID)).thenReturn(futureSeckill);

        try {
            reservationService.pullReminders(SECKILL_ID);
            fail("应抛出活动未开始异常");
        } catch (RuntimeException e) {
            assertEquals("秒杀活动未开始，无法拉取提醒", e.getMessage());
        }
        verify(reservationMapper, never()).findPendingBySeckillId(anyLong());
    }

    @Test
    public void testPullRemindersAfterEnd() {
        when(seckillMapper.findById(SECKILL_ID)).thenReturn(endedSeckill);

        try {
            reservationService.pullReminders(SECKILL_ID);
            fail("应抛出活动已结束异常");
        } catch (RuntimeException e) {
            assertEquals("秒杀活动已结束", e.getMessage());
        }
        verify(reservationMapper, never()).findPendingBySeckillId(anyLong());
    }

    // ==================== 提醒消费幂等 ====================

    @Test
    public void testPullRemindersIdempotent() {
        when(seckillMapper.findById(SECKILL_ID)).thenReturn(activeSeckill);

        // 第一次拉取：有待提醒记录
        SeckillReservation r1 = new SeckillReservation();
        r1.setSeckillId(SECKILL_ID);
        r1.setUserPhone(USER_PHONE);
        r1.setStatus(0);
        when(reservationMapper.findPendingBySeckillId(SECKILL_ID))
                .thenReturn(Arrays.asList(r1))    // 第一次：有待提醒
                .thenReturn(Collections.emptyList()); // 第二次：已被消费，无待提醒
        when(reservationMapper.markConsumedBySeckillId(SECKILL_ID)).thenReturn(1);

        // 第一次调用
        List<SeckillReservation> first = reservationService.pullReminders(SECKILL_ID);
        assertEquals(1, first.size());
        verify(reservationMapper, times(1)).markConsumedBySeckillId(SECKILL_ID);

        // 第二次调用（幂等：status=0的记录已全部变为1，查不到待提醒）
        List<SeckillReservation> second = reservationService.pullReminders(SECKILL_ID);
        assertTrue(second.isEmpty());
        // markConsumed不应被再次调用（因为findPending返回空）
        verify(reservationMapper, times(1)).markConsumedBySeckillId(SECKILL_ID);
    }

    @Test
    public void testPullRemindersIdempotent_MarkConsumedAffectsZero() {
        // 并发场景：findPending查到记录，但markConsumed时已被其他线程消费
        when(seckillMapper.findById(SECKILL_ID)).thenReturn(activeSeckill);

        SeckillReservation r1 = new SeckillReservation();
        r1.setSeckillId(SECKILL_ID);
        r1.setStatus(0);
        when(reservationMapper.findPendingBySeckillId(SECKILL_ID)).thenReturn(Arrays.asList(r1));
        when(reservationMapper.markConsumedBySeckillId(SECKILL_ID)).thenReturn(0); // 已被其他线程消费

        // 不应抛异常，仍返回查到的列表
        List<SeckillReservation> result = reservationService.pullReminders(SECKILL_ID);
        assertEquals(1, result.size());
    }
}
