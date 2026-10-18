# LangChain4j 1.22.0 兼容性实验

## 范围

本目录是阶段 0 的隔离 Maven 工程，不参与 LearnHub 生产构建，也没有读取真实模型密钥或调用真实供应商。实验固定 JDK 17、Spring Boot 依赖管理 3.3.5、LangChain4j BOM 1.22.0，使用普通 Java 依赖和 Spring 手工 `@Bean`，未使用 LangChain4j Spring Boot Starter。

官方 API 依据：

- [Getting Started](https://docs.langchain4j.dev/get-started/)：BOM 与 JDK 要求。
- [OpenAI-compatible models](https://docs.langchain4j.dev/integrations/language-models/open-ai-compatible/)：OpenAI-compatible 模型配置。
- [Response Streaming](https://docs.langchain4j.dev/tutorials/response-streaming/)：`StreamingHandle.cancel()` 的连接取消语义。
- [AI Services](https://docs.langchain4j.dev/tutorials/ai-services/)：`TokenStream`、工具和 `ChatMemoryProvider`。
- [Chat Memory](https://docs.langchain4j.dev/tutorials/chat-memory/)：ChatMemory 与正式历史的边界。

## 执行

```powershell
cd D:\Project\myproject\LearnHub\experiments\langchain4j-compat
D:\Android\apache-maven-3.9.10\bin\mvn.cmd clean test
D:\Android\apache-maven-3.9.10\bin\mvn.cmd verify
D:\Android\apache-maven-3.9.10\bin\mvn.cmd dependency:tree
```

测试只绑定 MockWebServer 的临时回环端口，`@AfterEach` 会关闭服务器。Windows 受限沙箱若禁止创建 JDK HttpClient 的回环选择器，需要在允许回环网络的环境执行；这属于运行环境权限，不是产品后端端口。

## 2026-10-18 实测结果

`mvn clean test` 和 `mvn verify` 均通过，共 14 个测试，0 失败、0 错误、0 跳过：

| 能力 | 结果 |
| --- | --- |
| 同步响应与 usage | 能读取正文及 input/output/total token |
| 真正 SSE 增量 | 服务端按块输出，回调实际收到两个独立 delta |
| DeepSeek 重复完整工具 ID | `accumulateToolCallId(false)` 后 ID 保持 `call_same`，不会拼成重复 ID |
| 取消 | 在 `PartialResponseContext` 调用 `StreamingHandle.cancel()` 后不再收到后续回调 |
| 超时、断流、供应商错误 | 分别进入异常或 `onError`，没有被当成成功终态 |
| AI Services 工具循环 | 一次工具请求、一次工具结果回填和继续生成成功 |
| ChatMemory | 同一 `@MemoryId` 的后续请求包含前一轮用户消息和助手回答 |
| 每轮保护 | 包装 `StreamingChatModel.doChat` 后，初始调用和工具后的自动模型回合都会先进入 guard；第二轮可被预算拒绝且不会发出 HTTP 请求 |
| Spring 手工 Bean | Boot 3.3.5 管理的 Spring 6.1.14 上下文可创建同步和流式模型 Bean |
| 请求日志 | 模型显式关闭 `logRequests`/`logResponses`；测试请求体不含 Mock key |
| 首字前与首字后取消 | 自定义 `HttpClient` 在 `execute` 返回前登记取消动作；同步响应头后的停顿读取、SSE 首事件前及首事件后均可由外部取消，取消返回后没有迟到回调 |
| 响应资源上限 | 同步响应、流式成功响应和供应商错误体统一限制为 262144 字节，超限立即失败 |
| 传输线程生命周期 | 固定大小线程池和有界队列由 builder 持有，显式 `close()` 后线程退出且每次 operation 的取消注册被清理 |

依赖树锁定结果：LangChain4j `1.22.0`，OpenAI 模块同时带入 `langchain4j-reactive-streaming:1.22.0-beta32`，默认 HTTP 实现为 `langchain4j-http-client-jdk:1.22.0`；Jackson 最终为 Boot 3.3.5 管理的 `2.17.2`，未发现 Jackson 版本分叉。

## 结论与边界

- 普通 Java 依赖路线在本实验覆盖范围内兼容 JDK 17 与 Boot 3.3.5，可进入生产适配设计；该结论不等同于整个 LearnHub 已完成生产集成。
- DeepSeek 类接口应同时为同步和流式模型关闭原始日志，流式模型设置 `accumulateToolCallId(false)`。该选项只改变工具 ID 合并方式；工具名和参数仍按增量协议拼接。
- 业务 guard 应包装最底层 `ChatModel` / `StreamingChatModel` 的每次 `doChat`，只包 AI Service 外层方法会漏掉工具后的自动模型回合。
- 模型对象没有 `close()` / `AutoCloseable` 生命周期。默认 JDK HTTP 实现由 SDK 管理；本次 Surefire JVM 在所有 Mock 服务关闭后正常退出，没有遗留测试进程。生产若改用自定义 Executor/HTTP 客户端，必须由 Spring Bean 显式管理其关闭。
- SDK 原生 `HttpClient.execute(request, parser, listener)` 返回 `void`，高层 `StreamingHandle` 只在流式回调上下文中提供，因此默认路径在首个 delta 前没有业务层可持有的取消句柄。本实验的自定义 SPI 改用 JDK 17 `sendAsync(..., BodyHandlers.ofInputStream())`，在发起网络请求前把 `future.cancel(true)` 与 `InputStream.close()` 组合动作登记到当前 run，覆盖首字前、首字后和同步响应体读取取消。Mock 已证明取消后本地回调停止，但真实 DeepSeek 是否立即停止服务端计算和计费仍未验收，不能宣称取消必然免计费。
- JDK `HttpRequest.timeout` 不能作为响应头到达后的流式 body 空闲超时；同步和异步路径都必须由生产 `AssistantIoScope` 的硬截止定时触发同一取消动作。构造器的 `Consumer<Runnable>` 与 `Supplier<AutoCloseable>` 是有序的单 run 实验接口：生产应让 `scope.onCancel(...)` 返回该 operation 独立持有的 registration，终态关闭 registration，不能用跨 run 共享的 `AtomicReference`。
- 本阶段未读取或使用真实凭据，因此真实 DeepSeek 首字延迟、usage 缺失、网络中断后的服务端状态和取消计费均保留为阶段 3 的真实烟测缺口；这不代表已检查本机缺少凭据。
