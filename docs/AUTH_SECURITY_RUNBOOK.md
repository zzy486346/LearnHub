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

2026-10-09 验收时，开发环境未配置 `LEARNHUB_JWT_PRIVATE_KEY_PATH` 与 `LEARNHUB_JWT_PUBLIC_KEY_PATH`，该轮没有写入真实 `.env` 值，也没有启动、停止或重启后端服务。当时仅完成代码级启动拒绝、有状态 Redis API 模型和临时 PEM 跨实例验证；随后完成的真实基础设施本机验收见第 6 节。目标生产环境仍须按第 3 节验收，不能将本机测试结果视为生产验收完成。

## 5. 本次交付验证记录（2026-10-09）

- 根目录 Maven `test`、`verify` 均为 `BUILD SUCCESS`：88 项中 76 项执行通过，12 项需显式开启的基础设施集成测试跳过，无失败或错误。
- 最终 RSA 专项 8 项通过；生产防绕过测试直接激活 `StandardEnvironment` 的 `prod`、`production` profile，通过实际公共构造器验证固定密钥检查。
- 全部前端脚本 43 项通过（认证 17 项）；`npm run build` 包含 Vue TypeScript 检查，构建通过，既有大体积 chunk 警告保留。
- `git diff --check` 通过；修改在 `feature/auth-token-revocation` 分支按中文 Conventional Commits 提交，不自动合并或推送。
- 本机沙箱限制 JVM/Mockito 附加，受限测试已中断并确认进程退出，改用允许 JVM 附加的执行环境完成 Maven 校验。测试未启动监听端口或日常后端服务。

## 6. 真实基础设施本机验收（2026-10-11）

用户确认暂时没有目标生产部署环境，本轮完成本机隔离验收。新增 `AuthInfrastructureIntegrationTest` 使用真实 HTTP、MySQL、Redis、JWT 签发/验签、安全过滤链和数据库事务；只有与本次验收无关的媒体存储服务使用测试替身。两套认证应用以 `prod` profile 运行，绑定随机的 loopback HTTP 端口，共享同一组临时 PEM 文件和隔离数据库。

### 一键复现

前提：Docker 运行时已启动，本地存在 `mysql:8.4`、`redis:7.4-alpine` 镜像，JDK 17 和 Maven 3.9.10 可用。脚本使用 `--pull never`，不会自动下载镜像，也不会启动或重启用户日常后端。

```powershell
Set-Location D:\Project\myproject\LearnHub
& .\scripts\auth\verify-infrastructure.ps1
```

脚本创建带随机名称和归属标签的两个测试容器，将端口动态映射到 `127.0.0.1`。MySQL 数据目录使用 tmpfs；脚本把现有 schema 复制到 Git 忽略的 `.local` 临时目录，仅将两个数据库声明改为 `learnhub_auth_it`，不修改原始 schema，不加载业务种子数据，不连接已有业务库。容器里的演示凭据仅用于本次独立测试。

脚本显式设置 `learnhub.auth.it.enabled=true` 并传入随机 MySQL/Redis 端口，校验本次 Surefire 报告实际执行至少 5 项且无跳过、失败或错误；Maven 成功但测试被跳过不能算验收成功。直接运行该测试类时可以通过 `learnhub.auth.it.mysql-port`、`learnhub.auth.it.redis-port` 指定独立基础设施端口（默认分别为 13306、16379），默认不启用该集成测试。

### 已验证结果

最终版 5 项全部执行通过，`0 failures / 0 errors / 0 skipped`：

1. 通过真实 HTTP 退出后，旧 Access Token 请求返回 401，旧 Refresh Token 刷新返回 409；Redis denylist 和刷新白名单状态正确。
2. 同一 Refresh Token 向两个实例并发刷新，仅一个请求成功；旧令牌不能重放。
3. 修改密码真实更新 MySQL 的 `token_version`，另一实例拒绝修改前的 Access/Refresh Token，新密码登录并跨实例访问可用。
4. 两实例读取同一固定 PEM 后能验证彼此签发的令牌，并共享 Redis 撤销状态。
5. 关闭并重新创建第二个认证应用实例后，原有 Access Token 仍可验证，Redis 中原有 Refresh Token 可轮换且不能重复使用。

这里的实例重启是同一测试 JVM 内的 Spring 应用上下文与嵌入式 HTTP 服务重建，并非跨 JVM 进程重启、生产 Secret 挂载或负载均衡滚动发布。PEM 由 JUnit 临时目录管理，未写入实际 `.env`，也没有提交密钥。真实部署的进程重启、Secret 持久化和发布验证仍保留为第 3 节的后续验收。

### 清理与完整校验

- 测试只删除本次创建的用户、角色关联和 JWT `jti` 键；不使用 `FLUSHDB` 或全表删除。清理失败的条目保留至最终清理重试，并报告错误。
- 无论成功或失败，测试尝试关闭两个应用实例及执行线程，验证随机 HTTP 端口释放。脚本在 `finally` 中精确查询容器名称、验证归属标签后删除本次容器和临时 schema；Docker 查询或删除失败必须报错，不能伪报清理成功。
- 最终验收 JVM（PID 46312）已退出，随机 HTTP/基础设施端口已释放，本次隔离容器与临时 schema 目录均无残留；未停止用户服务或删除既有数据卷。
- 根目录 Maven `test`、`verify` 均为 `BUILD SUCCESS`：默认 93 项中 76 项执行通过、17 项 opt-in 测试跳过。新增 5 项认证集成测试已通过上述隔离脚本单独显式执行；其余 12 项基础设施测试本轮未执行。
- 代码与脚本审查通过，PowerShell 语法和 `git diff --check` 通过。改动在 `feature/auth-infrastructure-verification` 分支提交，不自动合并或推送。
