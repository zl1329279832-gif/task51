# 预约提醒子系统

## 先搞清楚一件事：预约 ≠ 下单

`ReservationController` 管的是**"开抢前提醒我"**这个功能，和真正的秒杀下单（`executeSeckill`）是两套完全独立的流程。

- **预约（reserve）：** 用户在活动开始前登记"到点了提醒我"，写入 `seckill_reservation` 表。
- **下单（executeSeckill）：** 用户在活动进行中点击秒杀，扣库存、插订单，写入 `seckill` 和 `seckill_order` 表。

预约了不代表能买到，没预约也可以直接秒杀。两者之间没有任何前置依赖关系。

---

## ReservationController 四个接口

基础路径：`/seckill/reservation`

### 1. 创建预约 —— POST `/{seckillId}/reserve`

- **用途：** 用户预约某个秒杀活动的开抢提醒。
- **鉴权：** 读 Cookie `killPhone`，没有则返回 `{"success": false, "error": "未注册"}`。
- **限制：** 只能预约**尚未开始**的活动（`now < startTime`），已经开始或结束的不让预约。
- **防重复：** 两层去重——先查 Redis Set `seckill:reservation:{seckillId}` 是否已包含该手机号，再靠数据库唯一索引 `(seckill_id, user_phone)` 兜底（`INSERT IGNORE`）。
- **返回：** `SeckillResult<SeckillReservation>`，成功时带上创建的预约记录。

### 2. 取消预约 —— DELETE `/{seckillId}/cancel`

- **用途：** 用户取消之前的预约。
- **鉴权：** 同上，读 Cookie。
- **限制：** 活动已经结束的不允许取消（没意义了）。
- **返回：** `SeckillResult<Boolean>`，`true` = 成功删除，`false` = 没找到对应预约。

### 3. 我的预约列表 —— GET `/my`

- **用途：** 查当前用户的所有有效预约。
- **鉴权：** 同上，读 Cookie。
- **过滤：** 自动排除已结束活动的预约，只返回活动还没结束的。
- **返回：** `SeckillResult<List<SeckillReservation>>`。

### 4. 拉取待提醒列表 —— GET `/{seckillId}/reminders`

- **用途：** 给后台定时任务或消息系统消费用的——拉出"该提醒了但还没提醒"的预约记录。
- **鉴权：** 无（不需要 Cookie），设计为内部系统调用。
- **返回：** `SeckillResult<List<SeckillReservation>>`，返回的是本次被消费的记录列表。

详细规则见下面的 pullReminders 说明。

---

## pullReminders 时间窗口

`pullReminders(seckillId)` 只在活动的**有效时间窗口内**才能执行：

```
startTime <= 当前时间 <= endTime
```

- 活动还没开始（`now < startTime`）→ 抛异常 `"活动尚未开始"`
- 活动已经结束（`now > endTime`）→ 抛异常 `"活动已结束"`

**设计意图：** 提醒只在活动进行时才有意义。太早拉没有用（用户还不能下单），太晚拉也没有用（活动已经结束了）。

---

## pullReminders 幂等规则

这个接口做到了**幂等消费**——同一条预约记录只会被拉取一次，重复调用不会产生重复提醒。

实现原理：

1. 整个方法跑在一个数据库事务（`@Transactional`）里。
2. 先 `SELECT` 所有 `status = 0`（待提醒）的记录。
3. 紧接着 `UPDATE seckill_reservation SET status = 1 WHERE seckill_id = ? AND status = 0`，把这些记录的状态从 0（待提醒）改成 1（已提醒）。
4. 返回第 2 步查到的记录列表。

因为 SELECT 和 UPDATE 在同一个事务里原子执行：

- **第一次调用：** 拿到所有 `status=0` 的记录，标记为 `status=1`，返回列表。
- **第二次调用：** `status=0` 的记录已经没有了，返回空列表。
- **并发调用：** 数据库行锁保证同一行不会被两个事务同时更新，不会重复消费。

| 状态值 | 含义 |
|--------|------|
| `0` | 待提醒（pending） |
| `1` | 已提醒（reminded / consumed） |

---

## 预约去重的 Redis 缓存

预约时用了一个 Redis Set 做快速去重：

| Redis Key | 类型 | 内容 |
|-----------|------|------|
| `seckill:reservation:{seckillId}` | Set | 集合成员是已预约的 `userPhone`（Long） |

- `reserve` 时先 `SISMEMBER` 判断是否已预约，通过后 `SADD` 加入。
- `cancel` 时 `SREM` 移除。
- 数据库唯一索引 `(seckill_id, user_phone)` + `INSERT IGNORE` 是最终兜底，即使 Redis 挂了也不会出现重复预约。

---

## 对接注意事项

1. **预约接口不是下单接口。** 测试秒杀下单请走 `POST /{seckillId}/{md5}/execution`，不要测 reservation 的 reserve。
2. **pullReminders 是消费型接口。** 调一次就消费掉了，第二次调同一个 seckillId 会返回空列表，这是正常行为不是 bug。
3. **pullReminders 没有鉴权。** 它面向内部系统，不要暴露给前端。
4. **预约只能在活动开始前创建。** 活动已经开始了就不让预约了，这时候应该直接去秒杀。
