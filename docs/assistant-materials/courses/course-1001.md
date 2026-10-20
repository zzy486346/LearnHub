# Spring Boot 3 实战

依据 2026-10-20 本地课程目录生成的学习补充资料，非原授课逐字稿。本文是对应 JSON 导入正文的阅读版。

## 创建第一个 Spring Boot 项目

来源标识：generated.course.1001.lesson.3001

### 概念入门与课程主题

来源声明：本资料依据课程目录编写，是 AI 生成学习资料，非原授课逐字稿。Spring Boot 3 实战关注如何用约定、自动配置和依赖管理快速搭建可运行的 Java Web 应用。本课先建立整体认识：项目至少包含构建文件、启动类、配置文件和业务代码。JDK 17 是本课程运行基线，Spring Boot 3.3.5 负责组织 Spring 容器、嵌入式 Web 服务器以及常见组件的默认配置。

### 启动机制

启动类通常使用 @SpringBootApplication，它组合了配置类、自动配置和组件扫描三项能力。main 方法调用 SpringApplication.run 后，框架创建应用上下文，扫描启动类所在包及其子包中的组件，根据 classpath 和配置条件装配 Bean，最后启动嵌入式服务器。控制器只有被扫描到并成功注册，HTTP 路由才会存在，因此启动类应放在项目较高层的根包。

### 可执行示例

可以创建 DemoApplication 启动类，再创建带 @RestController 的 HelloController，并声明 @GetMapping("/hello") 方法返回字符串。使用 Maven 执行 mvn spring-boot:run，看到应用成功监听端口后访问 http://localhost:8080/hello。若返回预期文本，说明编译、容器初始化、控制器扫描和请求映射这条最小链路已经贯通。随后可用 application.yml 将 server.port 改为独立测试端口，避免占用正在运行的服务。

### 常见误区与边界

只创建 Java 类并不会自动成为 Bean，类需要位于扫描范围并带有合适的组件注解。依赖版本不应随意混搭，Boot 的依赖管理已经为常用库选择兼容组合；本课程保持 JDK 17 和 Boot 3.3.5。应用启动成功也不等于业务正确，还要验证路由、状态码和响应内容。开发环境可用本地默认值，但密钥与数据库密码应从本地配置读取，不应写入源码或提交到仓库。

### 自测与参考答案

自测一：为什么建议把启动类放在根包？参考答案：默认组件扫描从启动类所在包向下进行，位置过深可能漏掉控制器或服务。自测二：访问 /hello 得到 404，应依次检查什么？参考答案：检查应用端口、请求路径、控制器注解、映射注解和包扫描范围。自测三：最小项目的验证目标是什么？参考答案：构建成功、应用上下文启动、服务器监听，并且一个实际 HTTP 请求得到约定响应。

检索自测问题：一个适配 JDK 17 的 Spring Boot 3 项目从启动类到接收 HTTP 请求需要哪些关键组成？

## 配置分层架构与环境参数

来源标识：generated.course.1001.lesson.3002

### 概念入门与课程主题

来源声明：本资料依据课程目录编写，是 AI 生成学习资料，非原授课逐字稿。本课把 Spring Boot 工程从“能运行”推进到“职责清楚、环境可切换”。常见分层包括 controller、application 或 service、domain 以及 infrastructure。控制器负责协议转换，应用服务编排用例，领域对象表达业务规则，基础设施层处理数据库、消息和外部系统。配置则把端口、连接地址和开关从代码中移出。

### 分层与配置解析机制

调用方向应从外向内：控制器调用应用服务，应用服务依赖领域能力或接口，基础设施实现这些接口。Spring 配置具有明确优先级，命令行、环境变量、Profile 专属文件和基础 application.yml 会按规则覆盖。可在基础文件放所有环境共享的键，在 application-local.yml、application-prod.yml 中只放差异，并用 spring.profiles.active 或环境变量选择环境。@ConfigurationProperties 适合将一组相关配置绑定成类型安全对象。

### 可执行示例

例如定义 app.payment.mode 与 app.payment.timeout，再创建带 @ConfigurationProperties(prefix="app.payment") 的记录或 JavaBean。基础文件给出安全默认超时，本地 Profile 选择模拟支付，生产 Profile 从环境变量读取真实模式。启动时分别设置 SPRING_PROFILES_ACTIVE=local 和 prod，打印已激活 Profile 并调用一个只读配置检查接口。测试应断言两种环境产生不同的实现选择，同时公共超时仍被正确绑定。

### 常见误区与边界

分层不是把每个方法机械地复制到 Controller、Service、Manager 多层，而是按变化原因划分职责。领域层不应依赖 HTTP 请求对象或具体数据库 Mapper。生产密钥不要放入任何 application-*.yml；占位符可以有非敏感默认值，密钥项则应要求外部提供。Profile 解决环境差异，不适合承担用户级动态功能开关。修改配置后是否热生效取决于具体机制，普通绑定 Bean 通常需要重启应用。

### 自测与参考答案

自测一：数据库查询代码应放在哪一层？参考答案：具体实现位于基础设施层，业务层依赖抽象接口。自测二：本地和生产只有数据库地址不同，如何组织？参考答案：公共键放基础文件，差异地址放各自 Profile 或环境变量。自测三：为什么优先选择 @ConfigurationProperties 而非散落的 @Value？参考答案：它能按前缀集中绑定、支持类型校验，便于测试与维护。

检索自测问题：Spring Boot 项目怎样用分层架构和 Profile 隔离本地与生产环境配置？

## 设计 REST API 与统一响应

来源标识：generated.course.1001.lesson.3003

### 概念入门与课程主题

来源声明：本资料依据课程目录编写，是 AI 生成学习资料，非原授课逐字稿。REST API 用资源、HTTP 方法和状态码表达服务能力。本课覆盖 URL 设计、请求校验、统一成功响应与错误响应。资源名通常使用名词复数，如 /api/courses；GET 查询、POST 创建、PUT 整体替换、PATCH 局部更新、DELETE 删除。接口设计的目标是让调用者仅凭契约就能判断怎样请求以及结果代表什么。

### 请求到响应的机制

Spring MVC 根据路径和方法匹配控制器，再将路径变量、查询参数或 JSON 请求体绑定为 Java 对象。使用 jakarta.validation 注解描述输入约束，并在参数前加 @Valid 触发校验。业务成功后可直接返回资源或统一的 ApiResponse；发生异常时由 @RestControllerAdvice 集中转换。HTTP 状态码表达协议层结果，业务错误码提供稳定的程序判断依据，message 面向人类说明。

### 可执行示例

设计创建课程接口 POST /api/courses，请求体包含 title 和 description。title 使用 @NotBlank，控制器把 DTO 交给应用服务；创建成功返回 201，并在响应中带新资源编号。若 title 为空，统一异常处理器返回 400、错误码 VALIDATION_FAILED 和字段错误列表；若查询不存在的课程，则返回 404 与 COURSE_NOT_FOUND。可用 MockMvc 分别验证成功、校验失败和资源不存在三条路径。

### 常见误区与边界

不要所有情况都返回 HTTP 200 再依靠业务码区分，否则代理、监控和通用客户端无法正确理解失败。统一响应不应把堆栈、SQL 或内部异常类暴露给前端。DTO 与数据库实体职责不同，直接把实体作为请求体容易产生越权字段绑定。GET 应保持只读和幂等，不能因为“调用方便”就触发写操作。分页接口还要限制 pageSize，防止一次读取过量数据。

### 自测与参考答案

自测一：创建成功更适合返回什么状态码？参考答案：201 Created，并返回新资源或其位置。自测二：参数格式合法但资源不存在属于什么错误？参考答案：通常返回 404，并给出稳定业务错误码。自测三：统一响应里哪些信息应稳定？参考答案：状态语义、业务错误码和数据结构；面向用户的文字可以演进。接口测试至少覆盖正常路径、输入校验和核心业务异常。

检索自测问题：设计 REST API 时怎样选择 HTTP 方法、状态码并统一错误响应？

## 使用 MyBatis-Plus 完成数据访问

来源标识：generated.course.1001.lesson.3004

### 概念入门与课程主题

来源声明：本资料依据课程目录编写，是 AI 生成学习资料，非原授课逐字稿。MyBatis-Plus 在 MyBatis 映射能力上提供通用 Mapper、条件构造、分页等常用能力，减少重复 CRUD 代码。本课重点不是记忆方法名，而是建立数据访问边界：实体映射数据库行，Mapper 执行持久化操作，应用服务控制事务和业务规则，控制器不直接拼装数据库条件。

### 映射与条件机制

实体通过 @TableName 对应表，通过 @TableId 和 @TableField 对应主键及字段；遵循下划线转驼峰时可少写部分注解。BaseMapper 提供按主键查询和增删改等操作。LambdaQueryWrapper 使用方法引用表达字段，能够避免直接书写字符串列名。条件是否生效应由明确参数控制，例如关键词非空才添加 like，状态非空才添加 eq。分页需要安装并配置分页拦截器。

### 可执行示例

假设 course 表包含 id、title、status、created_at。定义 CourseEntity 和 CourseMapper extends BaseMapper<CourseEntity>。查询已上架且标题包含“Java”的课程时，构造 LambdaQueryWrapper，先 eq(CourseEntity::getStatus, PUBLISHED)，再 like(CourseEntity::getTitle, "Java")，最后按创建时间倒序。传入 Page.of(1, 20) 执行 selectPage，并将实体转换成响应 DTO。测试应准备两种状态记录，确认未上架课程不会返回。

### 常见误区与边界

Wrapper 参数仍需来自服务端允许的字段，不能让客户端传任意列名或 SQL 片段。通用 updateById 不会自动表达所有并发规则，库存或余额更新应使用带条件的原子 SQL 或乐观锁。逻辑删除字段只是过滤策略，不等于数据真的消失，也要考虑唯一索引。分页必须设置合理上限。N+1 查询不能靠通用 Mapper 自动解决，应批量查询、关联查询或重构返回模型。

### 自测与参考答案

自测一：LambdaQueryWrapper 相比字符串列名的主要优势是什么？参考答案：字段重构更安全，减少拼写错误。自测二：为什么控制器不应直接使用 Mapper？参考答案：会把事务、权限和业务规则散落到协议层。自测三：selectById 后返回 null 如何处理？参考答案：由应用服务转换成明确的资源不存在异常，再由统一异常处理映射为 404。分页测试还应验证页大小上限与排序稳定性。

检索自测问题：使用 MyBatis-Plus 查询课程时如何完成实体映射、条件构造和分页保护？
