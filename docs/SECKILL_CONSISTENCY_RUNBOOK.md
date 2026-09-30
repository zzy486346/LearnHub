# 秒杀一致性升级与验证手册

## 既有环境升级

新建数据库由 `sql/schema.sql` 直接获得最新结构。已有 MySQL 数据卷必须先执行一次：

```powershell
Get-Content -Raw sql/migrations/V20260929__seckill_consistency.sql |
  docker exec -i deploy-mysql-1 mysql -ulearnhub -plearnhub learnhub
```

消息队列已版本化为 `learnhub.coupon.seckill.v2`，避免给已存在的 v1 队列追加死信参数时发生声明冲突。升级前应暂停入口，确认旧队列无积压；若有积压，先由旧版本消费者排空，或按原消息内容补齐 `requestId/messageId/version` 后重放到 v2，不能直接删除消息。

Redis key 改为带 hash tag 的 `learnhub:coupon:{couponId}:*`，以支持 Redis Cluster 多键 Lua。部署后对账任务会在新库存 key 缺失时，以 MySQL 库存和订单状态原子重建 stock、用户集合与待确认 reservation。确认新 key 已生成后，旧的无 hash-tag key 才可人工清理。

## 自动化验证

默认单元测试：

```powershell
D:\Android\apache-maven-3.9.10\bin\mvn.cmd test
```

真实基础设施测试需要先启动 `deploy/docker-compose.yml` 中的 MySQL、Redis 和 RabbitMQ，并完成上述迁移，然后显式启用：

```powershell
D:\Android\apache-maven-3.9.10\bin\mvn.cmd -pl learnhub-server -am `
  "-Dlearnhub.it.enabled=true" `
  "-Dtest=SeckillInfrastructureIntegrationTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

该测试使用独立优惠券和用户 ID，验证超库存并发不会超卖、消息重复确认不会二次扣减，且最终 MySQL 订单、领取记录与库存一致。

## 故障恢复检查

- Publisher Confirm/Return 失败：订单保持 `PENDING`，对账任务按发布代次 CAS 重发。
- 消费重投：`mq_consume_log(message_id, consumer_name)` 与订单状态共同保证幂等。
- 消费最终失败：消息进入 DLQ；对账任务同时会重放长期未确认的 `SENT` 请求，达到上限后标记 `FAILED` 并执行幂等 Redis 回补。
- Lua 成功但进程在写订单前退出：reservation hash 保留 requestId；超时且 MySQL 无对应订单时，对账任务自动回补。
- Redis 数据丢失：仅在 stock key 缺失时从一个数据库一致性快照重建，避免覆盖在线预扣。
