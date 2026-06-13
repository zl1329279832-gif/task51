# 秒杀系统接口文档

给集成方和测试同学看的文档，不讲源码细节，只讲"调接口时你需要知道的事"。

## 文档目录

| 文档 | 说明 |
|------|------|
| [列表走缓存、下单走 DB —— 数据源策略](cache-and-db-strategy.md) | findAll / exportSeckillUrl / executeSeckill 为什么读不同的数据源，TTL 60 秒窗口内可能看到什么不一致 |
| [预约提醒子系统](reservation-reminder.md) | ReservationController 四个接口说明，pullReminders 时间窗和幂等规则，Reservation 和 executeSeckill 的关系 |
| [秒杀状态码对照表](seckill-status-codes.md) | SeckillStatEnum → SeckillController 返回值的完整映射，含外层 success 字段的坑 |

## 维护约定

- 改了接口逻辑就同步更新对应文档，PR 里带上 `doc/` 的 diff。
- 章节标题保持口语化，别写成 javadoc 风格。
- 状态码表如果加了新枚举值，记得同步 `seckill-status-codes.md`。
