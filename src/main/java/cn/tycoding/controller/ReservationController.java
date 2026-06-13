package cn.tycoding.controller;

import cn.tycoding.dto.SeckillResult;
import cn.tycoding.entity.SeckillReservation;
import cn.tycoding.service.ReservationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 秒杀预约提醒控制层
 *
 * 接口说明：
 * POST   /seckill/reservation/{seckillId}/reserve   预约提醒
 * DELETE /seckill/reservation/{seckillId}/cancel      取消预约
 * GET    /seckill/reservation/my                      查询我的预约
 * GET    /seckill/reservation/{seckillId}/reminders   拉取待提醒记录（活动进入可抢时间窗后）
 *
 * @auther TyCoding
 * @date 2018/10/8
 */
@RestController
@RequestMapping("/seckill/reservation")
public class ReservationController {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private ReservationService reservationService;

    /**
     * 预约秒杀提醒
     */
    @PostMapping("/{seckillId}/reserve")
    public SeckillResult<SeckillReservation> reserve(
            @PathVariable("seckillId") Long seckillId,
            @CookieValue(value = "killPhone", required = false) Long userPhone) {
        if (userPhone == null) {
            return new SeckillResult<>(false, "未注册");
        }
        try {
            SeckillReservation reservation = reservationService.reserve(seckillId, userPhone);
            return new SeckillResult<>(true, reservation);
        } catch (Exception e) {
            logger.error("预约失败: {}", e.getMessage());
            return new SeckillResult<>(false, e.getMessage());
        }
    }

    /**
     * 取消预约
     */
    @DeleteMapping("/{seckillId}/cancel")
    public SeckillResult<Boolean> cancel(
            @PathVariable("seckillId") Long seckillId,
            @CookieValue(value = "killPhone", required = false) Long userPhone) {
        if (userPhone == null) {
            return new SeckillResult<>(false, "未注册");
        }
        try {
            boolean result = reservationService.cancelReservation(seckillId, userPhone);
            return new SeckillResult<>(true, result);
        } catch (Exception e) {
            logger.error("取消预约失败: {}", e.getMessage());
            return new SeckillResult<>(false, e.getMessage());
        }
    }

    /**
     * 查询我的预约列表
     */
    @GetMapping("/my")
    public SeckillResult<List<SeckillReservation>> myReservations(
            @CookieValue(value = "killPhone", required = false) Long userPhone) {
        if (userPhone == null) {
            return new SeckillResult<>(false, "未注册");
        }
        try {
            List<SeckillReservation> reservations = reservationService.getUserReservations(userPhone);
            return new SeckillResult<>(true, reservations);
        } catch (Exception e) {
            logger.error("查询预约失败: {}", e.getMessage());
            return new SeckillResult<>(false, e.getMessage());
        }
    }

    /**
     * 拉取待提醒的预约记录
     * 仅在秒杀活动进入可抢时间窗（startTime <= now <= endTime）后可调用
     * 拉取后标记为已提醒，保证幂等消费
     */
    @GetMapping("/{seckillId}/reminders")
    public SeckillResult<List<SeckillReservation>> pullReminders(
            @PathVariable("seckillId") Long seckillId) {
        try {
            List<SeckillReservation> reminders = reservationService.pullReminders(seckillId);
            return new SeckillResult<>(true, reminders);
        } catch (Exception e) {
            logger.error("拉取提醒失败: {}", e.getMessage());
            return new SeckillResult<>(false, e.getMessage());
        }
    }
}
