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
 */
@RestController
@RequestMapping("/seckill/reservation")
public class ReservationController {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private ReservationService reservationService;

    /**
     * 预约秒杀提醒
     * POST /seckill/reservation/{seckillId}/reserve?userPhone=xxx
     */
    @PostMapping(value = "/{seckillId}/reserve", produces = "application/json;charset=UTF-8")
    public SeckillResult<SeckillReservation> reserve(@PathVariable("seckillId") Long seckillId,
                                                     @RequestParam(value = "userPhone", required = false) Long userPhone) {
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
     * POST /seckill/reservation/{seckillId}/cancel?userPhone=xxx
     */
    @PostMapping(value = "/{seckillId}/cancel", produces = "application/json;charset=UTF-8")
    public SeckillResult<Boolean> cancel(@PathVariable("seckillId") Long seckillId,
                                         @RequestParam(value = "userPhone", required = false) Long userPhone) {
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
     * 查询我的预约
     * GET /seckill/reservation/my?userPhone=xxx
     */
    @GetMapping(value = "/my", produces = "application/json;charset=UTF-8")
    public SeckillResult<List<SeckillReservation>> myReservations(@RequestParam(value = "userPhone", required = false) Long userPhone) {
        if (userPhone == null) {
            return new SeckillResult<>(false, "未注册");
        }
        try {
            List<SeckillReservation> list = reservationService.getUserReservations(userPhone);
            return new SeckillResult<>(true, list);
        } catch (Exception e) {
            logger.error("查询预约失败: {}", e.getMessage());
            return new SeckillResult<>(false, e.getMessage());
        }
    }

    /**
     * 拉取待提醒的预约记录（活动进入可抢窗口后调用）
     * GET /seckill/reservation/{seckillId}/reminders
     */
    @GetMapping(value = "/{seckillId}/reminders", produces = "application/json;charset=UTF-8")
    public SeckillResult<List<SeckillReservation>> pullReminders(@PathVariable("seckillId") Long seckillId) {
        try {
            List<SeckillReservation> list = reservationService.pullReminders(seckillId);
            return new SeckillResult<>(true, list);
        } catch (Exception e) {
            logger.error("拉取提醒失败: {}", e.getMessage());
            return new SeckillResult<>(false, e.getMessage());
        }
    }
}
