# 点击课程问答跳登录页：定位与修复（2026-10-17）

## 原因与复现

页面显示管理员姓名，是前端保存的登录状态；它不代表服务端每次请求都能识别身份。本轮已复现一个确定造成该现象的后端错误：

1. `POST /api/assistant/course-qa` 或 `/api/assistant/agent` 首次请求使用 Bearer JWT 正常通过认证。
2. Controller 使用 `DeferredResult` 异步处理；结果就绪后，Servlet 容器发起第二次 ASYNC 分派。
3. 自定义 `JwtAuthenticationFilter` 继承 `OncePerRequestFilter`，原代码默认跳过 ASYNC 分派。无状态认证没有在第二次分派恢复身份，`anyRequest().authenticated()` 因而返回 401。
4. 前端原有的助理 POST 401 分支立即清理登录令牌并跳转登录页，所以用户看到“明明已登录，点击问答却退出”。

新增回归在旧代码下真实观察到：RAG、Agent 两个接口均为 `Status expected:<200> but was:<401>`。此前测试只验证 `asyncStarted`，未继续分派读取最终 HTTP 响应，因此漏掉此问题。

Spring 对默认行为的说明见 [OncePerRequestFilter 6.1.14](https://docs.spring.io/spring-framework/docs/6.1.14/javadoc-api/org/springframework/web/filter/OncePerRequestFilter.html#shouldNotFilterAsyncDispatch())。

## 修复与交互

- 后端覆盖 `shouldNotFilterAsyncDispatch()` 返回 false，在 ASYNC 分派重新验证 JWT、用户状态、版本、吊销标记与角色。正常结果不再变成 401；真正退出/撤权仍然拒绝。没有直接放行所有 ASYNC 请求或取消认证。
- 前端助理 POST 收到 401 时，可恢复有效的 Refresh Token 会话，但绝不自动重放可能已经计费的生成请求。
- 会话恢复成功时保留原问题，显示“登录状态已更新，本次问题尚未重新提交，请再次点击‘开始问答’”。刷新被拒绝才清理登录并跳转；刷新网络故障或 5xx 保留凭据。
- 并发普通请求与助理请求共用一次刷新；普通请求可按原机制重放，助理只能由用户手动再次提交。账号切换仍受 session generation 隔离。

## 验证证据

| 检查 | 实际结果 |
| --- | --- |
| 旧代码复现 | 7 项中 2 项失败：两个合法异步结果均被改为 401 |
| 修复针对测试 | 11 执行、0 跳过、0 失败：Controller 10 + SessionVerifier 1 |
| 真实 Servlet / HTTP | 随机回环端口 4/4：合法响应 200、业务 503 保持、处理期间退出登录最终 401、匿名请求不能进入 Controller |
| `mvn test` | 315 总数、258 执行、57 跳过、0 失败/错误 |
| `mvn verify` | 315 总数、258 执行、57 跳过、0 失败/错误；打包成功 |
| 前端针对回归 | 35 执行、0 跳过、0 失败 |
| 前端全部回归 | 87 执行、0 跳过、0 失败 |
| `npm run build` | vue-tsc 与 Vite 均通过，原有大包体积告警仍在 |

测试覆盖会话刷新成功且原生成只发送一次、刷新失效跳转、刷新断网/500 保留会话、混合并发只刷新一次以及面板保留问题/手动重试提示。真实 HTTP 使用固定的本地测试身份与受控异步结果，不读取用户令牌、不调用 DeepSeek，也不修改课程或学习进度。默认跳过的中间件/付费验收不算本轮通过，真实模型调用数为 0。

本地证据日志位于被忽略的 `.local/assistant-async-401-repro.log`、`assistant-async-401-fixed.log`、`assistant-async-http-fixed.log`、`assistant-async-final-test.log`、`assistant-async-final-verify.log`、`assistant-401-ui-targeted.log`、`assistant-401-ui-all.log`、`assistant-401-ui-build.log`。

## 使用与提交

后端需正常重启加载更新后的过滤器，前端需加载更新资源后生效。代理没有重启用户正在运行的后端。回归的随机端口测试上下文自动关闭，本次记录的测试 JVM 已退出；没有留下测试后端。

- `6820cac fix: 修复助理异步响应丢失登录身份`
- `3fcec27 test: 验证真实HTTP异步响应的鉴权与撤权`
- `16c5e82 fix: 助理认证失败刷新会话但不重放生成`

保留功能分支 `feature/assistant-rag-agent`，未合并、未推送。此修复不等于放宽课程权限，也不改变此前留出评测的错误拒答记录。


## 运行实例核验与用户授权重启

用户反馈重新登录后仍然反复出现“登录状态已更新”提示，后续核验发现前端已加载新提示，但 8080 上的旧后端进程 PID 117936 从 2026-10-17 15:01:54 一直运行；修复后的 JwtAuthenticationFilter.class 在 16:22:21 才编译。该进程使用工作区 target/classes，未启用 devtools 或调试热替换，因此重新登录仍然执行旧过滤器。

在完成上述只读诊断后，用户明确授权“允许，仅重启这个后端”。代理核对进程主类、创建时间及 8080 端口归属后，仅停止该后端。端口释放后使用已经通过 verify 的可执行 JAR 启动替代实例，没有重启前端或 MySQL、Redis、RabbitMQ、Elasticsearch 等中间件。

- 新后端 PID：120328；启动时间：2026-10-17 19:43:02；端口：8080。
- `GET /actuator/health` 返回 `UP`，8080 归属新 PID；旧 PID 已退出。
- 运行 JAR 中的 JWT 过滤器与已验证编译类逐字节一致，含 ASYNC 分派覆盖方法。
- 匿名访问 `/api/auth/me` 仍正确返回 401，没有通过放宽权限来消除提示。
- 启动日志确认使用临时 JWT RSA 密钥，因此重启前的令牌失效；重启后需重新登录一次，刷新前端后再使用问答。
- 没有读取用户浏览器令牌或密码，没有创建诊断账号或发起付费模型请求。当前用户真实登录后的请求尚未由代理代为执行。

本次是用户授权保留运行的替代后端，不属于需结束后清理的临时测试服务。运行元数据、构建哈希与日志路径保存在被 Git 忽略的 `.local/backend-restart-latest.json`。前文“未重启用户服务”对应取得本次明确授权之前的修复阶段。
