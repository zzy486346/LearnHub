# 认证令牌与 RSA 密钥运行手册

## 1. 认证安全约束

- Access Token 使用 RS256，有效期 15 分钟；退出时将其 `jti` 写入 Redis denylist，TTL 等于令牌剩余有效期。
- Refresh Token 有效期 7 天，仅在 Redis 保存 `auth:refresh:{jti}` 状态，不保存令牌明文；刷新使用 `GETDEL` 原子消费，旧令牌不能重放。
- 退出接口同时接收 Bearer Access Token 和请求体中的 Refresh Token。接口允许匿名访问，以便 Access Token 已过期时仍可撤销有效的 Refresh Token；服务端仅处理能够通过签名和类型校验的令牌。
- 修改密码递增用户 `tokenVersion`。安全过滤器和刷新接口都会对比数据库中的当前版本，因此修改密码前签发的 Access/Refresh Token 均失效。
- 前端退出会等待已开始的刷新请求收敛，再提交当前令牌对；无论请求成功、超时或服务端失败都会清理本地会话，并向用户明确提示服务端撤销是否失败。

## 2. 生产 RSA 密钥配置

生产部署必须启用 `prod`（或兼容名称 `production`）profile，并通过部署平台的密钥管理或只读 Secret 挂载提供固定密钥。生产 profile 的强制检查直接读取活动 profile，不能通过把 `learnhub.jwt.require-configured-keys` 覆盖为 `false` 绕过：

```properties
SPRING_PROFILES_ACTIVE=prod
LEARNHUB_JWT_PRIVATE_KEY_PATH=file:/run/secrets/learnhub-jwt-private.pem
LEARNHUB_JWT_PUBLIC_KEY_PATH=file:/run/secrets/learnhub-jwt-public.pem
```

私钥必须为 PKCS#8 PEM，公钥必须为 X.509 SubjectPublicKeyInfo PEM。RSA 位数不得低于 2048，建议新部署使用 3072 位：

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out jwt-private.pem
openssl pkey -in jwt-private.pem -pubout -out jwt-public.pem
```

不要把 PEM、Secret 内容或真实路径凭据提交到 Git。私钥文件只授予应用运行账号读取权限，公私钥应存放在容器外的持久化密钥存储中，并以只读方式挂载。滚动发布期间所有实例必须读取同一密钥对，否则旧实例签发的令牌会被新实例拒绝。

应用启动会拒绝以下配置：

- `prod` profile 未配置密钥；
- 只配置私钥或只配置公钥；
- 配置路径不存在；
- 公私钥不匹配；
- RSA 位数低于 2048；
- PEM 格式或密钥类型错误。

非生产环境未配置密钥时仍会生成临时 2048 位密钥并输出警告。临时密钥只用于本地开发，应用重启后旧令牌会失效。

## 3. 发布与重启验收

1. 在密钥管理系统中生成或导入密钥对，检查私钥权限和 Secret 挂载路径。
2. 使用 `prod` profile 启动一个候选实例，确认健康检查通过且日志中没有“generated an ephemeral development key pair”。
3. 登录候选实例取得令牌，经负载均衡分别访问至少两个实例，确认 Access Token 均可验签。
4. 保留令牌并滚动重启实例，再次调用受保护接口并刷新令牌，确认固定密钥在重启后保持一致。
5. 执行退出，确认旧 Access Token 请求受保护接口返回 401，旧 Refresh Token 调用 `/api/auth/refresh` 失败；重复退出应保持幂等。
6. 登录后执行修改密码，确认修改前的 Access/Refresh Token 均不可继续使用。

密钥轮换需要明确的令牌迁移策略。当前实现只加载一组公私钥，直接替换会立即使旧密钥签发的全部令牌失效；如需无感轮换，应另行实现 `kid` 与多公钥验签。

## 4. 回归验证

后端认证专项测试：

```powershell
D:\Android\apache-maven-3.9.10\bin\mvn.cmd -pl learnhub-server -am "-Dtest=AuthControllerSecurityTest,AuthTokenLifecycleTest,AuthServiceTest,JwtTokenServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

测试覆盖：退出后旧 Access/Refresh Token 失效、重复退出、过期 Access Token 不阻断 Refresh Token 撤销、刷新轮换不可重放、修改密码后旧双令牌失效、生产 profile 强制固定密钥、错误路径、单边配置、不匹配和弱密钥拒绝，以及临时 PEM 在两个 `JwtTokenService` 实例之间保持验签兼容。认证生命周期测试使用真实 JWT 签发/验签逻辑和有状态的 Redis API 模型，以验证 key、`GETDEL` 和 denylist 状态转换；它不等同于真实 Redis 集成测试。测试 PEM 写入 JUnit 临时目录，测试结束后由 JUnit 清理，不进入仓库。

前端认证回归执行：

```powershell
Set-Location D:\Project\myproject\LearnHub\learnhub-web
npm run test:auth
npm run test:vote
npm run test:coupon
npm run test:search
npm run test:admin
npm run build
```

本轮前端认证专项共 17 项通过，全部前端脚本共 43 项通过，构建通过并保留既有大体积 chunk 警告。覆盖项包括：等待已开始的刷新后再退出、退出失败或 401 时不自动刷新且仍清理会话、跨标签清理，以及阻止迟到的旧刷新响应恢复已退出会话或覆盖重新登录的新会话。

当前开发环境未配置 `LEARNHUB_JWT_PRIVATE_KEY_PATH` 与 `LEARNHUB_JWT_PUBLIC_KEY_PATH`，本轮没有写入真实 `.env` 值，也没有启动、停止或重启后端服务。因此这里只完成了代码级启动拒绝、有状态 Redis API 模型和临时 PEM 跨实例验证；尚未执行真实 Redis 生命周期集成、生产 Secret 注入、负载均衡多实例或部署滚动重启。目标环境必须按第 3 节验收，不能将本地测试结果视为生产验收完成。
