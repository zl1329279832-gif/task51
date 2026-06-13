package cn.tycoding.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;
import java.util.Date;

/**
 * 秒杀预约提醒表
 *
 * @auther TyCoding
 * @date 2018/10/8
 */
public class SeckillReservation implements Serializable {

    private long id; //主键ID

    private long seckillId; //秒杀商品ID

    private long userPhone; //用户手机号

    private int status; //状态：0-待提醒 1-已提醒

    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date createTime; //创建时间

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getSeckillId() {
        return seckillId;
    }

    public void setSeckillId(long seckillId) {
        this.seckillId = seckillId;
    }

    public long getUserPhone() {
        return userPhone;
    }

    public void setUserPhone(long userPhone) {
        this.userPhone = userPhone;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    @Override
    public String toString() {
        return "SeckillReservation{" +
                "id=" + id +
                ", seckillId=" + seckillId +
                ", userPhone=" + userPhone +
                ", status=" + status +
                ", createTime=" + createTime +
                '}';
    }
}
