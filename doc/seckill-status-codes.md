# 秒杀状态码对照表

## SeckillStatEnum → 接口返回值映射

`POST /seckill/{seckillId}/{md5}/execution` 的返回结构是：

```json
{
  "success": true,
  "data": {
    "state": 1,
    "stateInfo": "秒杀成功",
    "seckillOrder": { ... }
  }
}
```

下面是完整的状态码对照表：

| 枚举常量 | state | stateInfo | 什么时候会出现 | 对应的异常类 |
|---------|-------|-----------|--------------|-------------|
| `SUCCESS` | `1` | `秒杀成功` | 扣库存 + 插订单都成功了 | 无（正常返回） |
| `END` | `0` | `秒杀结束` | 活动不在时间窗口内，或库存已扣完 | `SeckillCloseException` |
| `REPEAT_KILL` | `-1` | `重复秒杀` | 同一个手机号对同一个活动重复下单 | `RepeatKillException` |
| `INNER_ERROR` | `-2` | `系统异常` | 未预期的运行时异常（DB 挂了之类的） | `SeckillException`（兜底） |
| `DATA_REWRITE` | `-3` | `数据串改` | 请求里的 md5 和服务端计算的不一致，说明 URL 被篡改了 | `SeckillException` |

---

## 注意：外层 success 字段的坑

这是个容易踩的坑：**不管秒杀成功还是失败，外层的 `success` 都是 `true`。**

| 场景 | 外层 `success` | 内层 `state` | 内层 `stateInfo` |
|------|---------------|-------------|-----------------|
| 秒杀成功 | `true` | `1` | `秒杀成功` |
| 秒杀结束 | `true` | `0` | `秒杀结束` |
| 重复秒杀 | `true` | `-1` | `重复秒杀` |
| 系统异常 | `true` | `-2` | `系统异常` |

只有一种情况外层 `success` 是 `false`：用户没有注册（Cookie 里没有 `killPhone`），这时候返回：

```json
{
  "success": false,
  "error": "未注册"
}
```

**所以判断秒杀是否成功，要看 `data.state == 1`，不能只看 `success == true`。**

---

## 判断逻辑建议

前端 / 集成方的推荐判断顺序：

```
1. response.success == false  →  用户未注册，引导去注册
2. response.data.state == 1   →  秒杀成功，展示订单
3. response.data.state == 0   →  秒杀结束，提示"来晚了"
4. response.data.state == -1  →  重复秒杀，提示"已经抢过了"
5. response.data.state == -2  →  系统异常，提示"稍后再试"
6. response.data.state == -3  →  数据篡改，不应该出现在正常流程里
```

---

## 补充：exposer 接口的返回结构

`POST /seckill/{seckillId}/exposer` 返回的是另一个结构，别和上面搞混：

```json
{
  "success": true,
  "data": {
    "exposed": true,
    "md5": "abc123...",
    "seckillId": 1000
  }
}
```

- `exposed: true` → 秒杀地址已暴露，拿着 `md5` 去调 execution 接口。
- `exposed: false` → 活动未开始、已结束、或库存为 0，不能秒杀。`exposed: false` 时会额外带上 `now`、`start`、`end` 三个时间戳，前端可以用来做倒计时。
