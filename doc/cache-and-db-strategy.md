# 列表走缓存、下单走 DB —— 数据源策略

## 一句话版本

浏览列表（`findAll`）从 Redis 缓存读，速度快但最多有 60 秒延迟；下单前的地址暴露（`exportSeckillUrl`）和真正扣库存（`executeSeckill`）都直接读数据库，保证时间窗口和库存判断是实时的。

---

## findAll 为什么走缓存

`SeckillServiceImpl.findAll()` 的读取流程：

1. 先尝试从 Redis Hash（key = `"seckill"`）里拿全部商品列表。
2. 缓存命中 → 直接返回，不碰数据库。
3. 缓存未命中 → 查数据库 `seckillMapper.findAll()`，把每条记录以 `seckillId` 为 hash field 写入 Redis，**整个 key 设 60 秒 TTL**。

`findById` 也是同样的套路：先查 Redis Hash 的某个 field，miss 了再回源 DB 并回填缓存。

**为什么这么做：** 商品列表是高频读、低频写的场景。首页/列表页的 QPS 远高于下单，缓存能挡掉绝大部分数据库压力。

---

## exportSeckillUrl 为什么走数据库

`exportSeckillUrl(seckillId)` 是用户点"立即秒杀"后、真正下单前的"暴露秒杀地址"步骤。它**故意不走缓存**，直接 `seckillMapper.findById(seckillId)` 从数据库读。

**原因：这一步要做时间窗口判断。** 它会拿当前时间和 `startTime` / `endTime` 比较，决定秒杀是否可以进行。如果从缓存读，可能拿到的是 60 秒前的快照——活动已经结束了，缓存里的 `endTime` 还没过期，用户就会拿到一个实际上无效的秒杀地址。

读完 DB 后，这个方法会顺手把最新数据**回填到 Redis 缓存**并重置 60 秒 TTL，相当于帮列表页刷新了一次缓存。

---

## executeSeckill 也走数据库

真正执行秒杀（扣库存 + 插订单）的 `executeSeckill` 同样直接读 DB 做二次校验：

1. 从数据库读 `startTime`、`endTime`、`stockCount`，确认活动在进行中且有库存。
2. 执行 `UPDATE seckill SET stock_count = stock_count - 1 WHERE ... AND stock_count > 0`，SQL 自带兜底条件。
3. 扣完库存后再把最新的 Seckill 记录回填到 Redis 缓存。

所以下单链路的数据准确性是有保障的，不会因为缓存延迟而超卖。

---

## 60 秒 TTL 窗口内你可能看到什么不一致

因为列表页走缓存（TTL 60 秒），下面这些情况是**正常的、符合预期的**：

| 场景 | 你会看到什么 | 原因 |
|------|-------------|------|
| 有人刚买完，库存从 10 变成 9 | 列表页可能还显示库存 10 | 缓存还没过期，最多延迟 60 秒 |
| 运营改了商品标题或价格 | 列表页可能还是旧的 | 同上 |
| 活动刚刚结束 | 列表页可能还显示"进行中" | 缓存里的 endTime 判断可能滞后 |
| 两个用户同一时刻看列表 | 一个看到新数据，一个看到旧数据 | 缓存恰好在两次请求之间过期了 |

**但不会影响下单：** 因为 `exportSeckillUrl` 和 `executeSeckill` 走的是数据库，时间窗口和库存校验都是实时的。列表页显示"有库存"但实际卖完了，用户点下单会正常收到"秒杀结束"的提示。

---

## 缓存结构速查

| Redis Key | 类型 | 内容 | TTL |
|-----------|------|------|-----|
| `seckill` | Hash | field = `seckillId`（Long），value = 序列化的 `Seckill` 对象 | 60 秒 |

序列化方式是 Jackson2Json（配置在 `RedisTemplateConfig`），所以用 `redis-cli` 看到的是 JSON 字符串。
