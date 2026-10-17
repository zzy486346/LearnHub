# LearnHub 学习助理 DeepSeek 接入说明

> 真实模型续验已完成，最新调用数、首次留出失败项与本地启用方式见 [DeepSeek 续验报告](ASSISTANT_DEEPSEEK_LIVE_ACCEPTANCE_20261017.md)。下文保留凭据到位前的阶段记录，原有“缺 Key / 调用 0”不代表最新状态。

本文记录 2026-10-16 核验的官方契约、LearnHub 的本地保护，以及本阶段真实调用验收边界。实现使用轻量 HTTP 适配器，没有升级 Spring Boot，也没有引入 AI 框架或供应商 SDK。

## 1. 模型与接口选择

- 固定调用 DeepSeek 官方 `POST https://api.deepseek.com/chat/completions`，生产配置不能修改供应商地址；测试只能通过包级构造器指向本机回环 HTTP Mock。
- 默认模型为 `deepseek-flash`，官方当前将其标识为 DeepSeek-V4.1-Flash。它支持 JSON Output 和 Tool Calls，适合本项目后续的受控工具循环。`deepseek-v4-pro` 也在本地允许列表中，但不是默认值。
- 每次请求显式发送 `thinking.type=disabled`、`stream=false`。这样工具回传不需要保存和重放推理正文，也不会把隐藏推理当作应用数据。
- 有工具定义时发送 `tool_choice=auto`；需要 JSON 时发送 `response_format.type=json_object`。

官方依据：[模型与价格](https://api-docs.deepseek.com/quick_start/pricing/)、[Chat Completions API](https://api-docs.deepseek.com/api/create-chat-completion/)、[Tool Calls](https://api-docs.deepseek.com/guides/tool_calls/)。

## 2. 结构化输出和工具边界

JSON Output 保证目标是有效 JSON，但不等于符合 LearnHub 的业务 Schema。官方还提示 JSON 模式偶尔可能返回空正文。因此应用仍须解析并校验字段；空正文、畸形 JSON、截断结果都按受控失败处理，不能直接交给页面。

工具调用会严格读取 `id、function.name、function.arguments`，其中 `arguments` 必须能解析为 JSON 对象。DeepSeek 的 Tool `strict` 仍属于 beta，需要 beta 地址，本阶段不使用。后续工具执行器仍须自行执行白名单、参数、身份和权限校验。

官方依据：[JSON Output](https://api-docs.deepseek.com/guides/json_mode/)、[Tool Calls strict 模式](https://api-docs.deepseek.com/guides/tool_calls/#strict-mode-beta)。

## 3. usage、结束原因与输出上限

适配器要求响应存在 `usage.prompt_tokens、usage.completion_tokens、usage.total_tokens`，并校验三者为非负整数且总数一致。缺少 usage 不会被当作成功，这使后续运行预算能够区分“已知消耗”和“计费未知”。Fake 模式的 usage 固定为 0，只表示没有真实调用。

官方当前列出的结束原因包括 `stop、tool_calls、length、content_filter、insufficient_system_resource、aborted`。LearnHub 只把 `stop` 的非空正文或 `tool_calls` 的有效工具请求视为正常结果；其他原因映射为明确错误。未知结束原因也拒绝继续。

供应商模型允许的输出远大于学习助理所需，本地把单次 `max_tokens` 限制为 1～2048，并再受 `LEARNHUB_AI_MAX_OUTPUT_TOKENS` 收紧。该限制用于控制成本和延迟，不表示输入可以无限增长。

官方依据：[Chat Completions 响应与 finish_reason](https://api-docs.deepseek.com/api/create-chat-completion/)、[模型输出上限](https://api-docs.deepseek.com/quick_start/pricing/)。

## 4. 超时、取消和响应大小

一次调用的有效截止时间取请求值与 `LEARNHUB_AI_REQUEST_TIMEOUT` 的较小值。截止时间覆盖连接、响应头和完整响应正文；即使服务端已经发出响应头后停住正文，也会取消底层 JDK HTTP future。调用线程被中断时同样取消 HTTP，并恢复线程的中断标记。

官方 Chat Completions 契约列出 `aborted` 结束原因，但没有提供可由本项目调用的独立服务端取消端点。因此本地取消只能保证 LearnHub 停止等待并取消客户端连接，不能承诺供应商一定停止推理，也不能据此承诺不会计费。真实调用仍由后续运行保护限制次数、累计 Token、并发和日额度。

响应使用有界 BodySubscriber，在接收过程中超过 `LEARNHUB_AI_MAX_RESPONSE_BYTES` 就取消订阅，不会先把无限正文完整放入内存。HTTP 401、429、超时、超大响应和畸形响应均返回本地错误码；供应商原始错误正文和 API Key 不进入异常消息。

官方依据：[错误码](https://api-docs.deepseek.com/quick_start/error_codes/)、[限速与隔离](https://api-docs.deepseek.com/quick_start/rate_limit/)。

## 5. 输入预算不是 Token 计数

发送前会先序列化完整请求，包括历史消息、工具定义和工具结果，再按 UTF-8 字节数与 `LEARNHUB_AI_MAX_INPUT_BYTES` 比较。字节预算是保守、可重复的本地保护，不能换算成精确 Token 数。真实 Token 只能以供应商返回的 usage 为准，后续运行保护应在调用前预留输出预算，在调用后用 usage 结算。

## 6. 配置与验收状态

真实模式使用 `LEARNHUB_AI_MODE=DEEPSEEK`。密钥优先读取 `LEARNHUB_DEEPSEEK_API_KEY`，也兼容官方常用的 `DEEPSEEK_API_KEY`；密钥为空时应用拒绝创建 DeepSeek 客户端。`.env.example` 只保留空值示例，真实密钥不得提交、打印或放入前端。

本阶段通过 Fake 与本地 HTTP Mock 验证请求字段、响应解析、错误映射、正文卡住超时、线程中断取消和响应字节上限。文档创建时没有发起付费真实调用，因此真实模型调用执行数为 0；这不算真实模型验收通过。取得本地密钥后，只执行受限的一次最小烟测，并单独记录实际调用数和 usage。
