# 全天 Demo 清单

四个递进分支对应前四个 Demo，每个分支只比上一个多一层能力。最后五个是基础设施 Demo，
代码从 `demo-1` 起就完整存在，**在任意分支上都能演示**。

| Demo | 分支 | 主题 |
|---|---|---|
| 1 | `demo-1` | 纵向切片与协程并发 |
| 2 | `demo-2` | Spring Security 与错误契约 |
| 3 | `demo-3` | 消息：持久化事件与消费者组 |
| 4 | `demo-4` | TDD、单元测试、契约测试 |
| 5 – 9 | 任意 | Gradle 构建、打包、Flyway、Profile、可观测性 |

---

## 课前一次性准备

冷启动首次构建约 3 分钟，Solace 首启还要 30–60 秒，**务必开课前做完**。

```bash
docker-compose up -d
docker-compose ps                 # 等两个都是 (healthy)
./gradlew test                    # 预热 Gradle 与依赖缓存
```

Colima 用户还要设置 Testcontainers 的 socket：

```bash
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

演示时常驻两个终端：

```bash
# 终端 A：Odds(8091) / Risk(8092) 桩服务
./gradlew runDemoDownstreams

# 终端 B：应用
./gradlew bootRun
```

**演示前状态检查**：

```bash
git status --short                              # 应为空
ls src/main/resources/db/migration/             # V4 应是 .sql.disabled
grep -rn "=== Demo TDD" src/ --include=*.kt     # demo-4 上应有 3 处，且都是注释
```

---

## Demo 1 — 纵向切片与协程并发

```bash
git checkout demo-1
```

**验证目标**

1. 一个请求依次经过哪几层，每层的职责边界在哪；
2. 目录名对应的是角色而不是技术，看名字就知道去哪找代码；
3. Odds 和 Risk 是**真并发**，而且「不同线程」不是并发的判据。

### 第 1 步 — 读代码（不写代码）

按请求路径依次打开：

| 文件 | 看什么 |
|---|---|
| `controller/BetController.kt` | `@RestController` + `@PostMapping`；`@Valid @RequestBody` 触发校验；`suspend` 函数 + `ResponseEntity.status(201)` |
| `dto/PlaceBetRequest.kt` | `@field:NotBlank` / `@field:DecimalMin` —— 校验写在契约上，不写在 service 里 |
| `service/BetService.kt` | `placeBet` 三步：`validateBet` → `saveBet`；`coroutineScope` + 两个 `async` |
| `repository/BetRepository.kt` | 一个端口两个适配器，`@Profile("database")` / `@Profile("!database")` |

包结构一句话带过：`controller` / `service` / `repository` / `domain` / `enum` / `dto` /
`client` / `configuration` / `exception`。

> 本分支还没有安全，客户身份走 `X-Customer-Id` 请求头。Demo 2 会把它换成 token 的
> subject —— 这正是「身份属于传输层，不能由调用方随便声明」的引子。

### 第 2 步 — 计时一次下注

```bash
# 预热一次并丢弃（首次含类加载与连接建立，会污染计时）
curl -s -o /dev/null -X POST http://localhost:8080/api/v1/bets \
  -H 'X-Customer-Id: C-100' -H 'Content-Type: application/json' \
  -d '{"gameId":"G-100","selection":"HOME","stake":10}'

curl -s -o /dev/null -w "耗时 %{time_total}s\n" -X POST http://localhost:8080/api/v1/bets \
  -H 'X-Customer-Id: C-100' -H 'Content-Type: application/json' \
  -d '{"gameId":"G-100","selection":"AWAY","stake":250}'
```

**期望结果**（已实测）：耗时约 **0.88 s**，即 `max(800, 600)`，不是 `1400 ms`。

终端 A（决定性证据，两个 `started` 都在任何 `completed` 之前）：

```
RISK  started   assessment
ODDS  started   gameId=G-100 selection=AWAY
RISK  completed assessment          ← 600ms 的先回
ODDS  completed gameId=G-100        ← 800ms 的后回
```

终端 B：

```
http-nio-8080-exec-1 | both validations launched, awaiting results
http-nio-8080-exec-1 | odds validation started
http-nio-8080-exec-1 | risk assessment started
ForkJoinPool.commonPool-worker-1 | bet accepted betId=B-1 ...
```

**讲解要点**

- **判据是时间戳，不是线程名。** 前三行在**同一个线程**上——按「不同线程才算异步」判断会得出错误结论。两个协程复用一个线程，正是 *concurrency without one thread per task*。
- 但 `bet accepted` 换到了 `ForkJoinPool` 线程：`await` 之后恢复到别的线程，HTTP 线程在等待期间被**释放**了。这是 `suspend` 与 `Thread.sleep` 的本质区别。
- 想不起服务也能证明：`./gradlew test --tests '*BetServiceTest'`（demo-4 上）用协程虚拟时间断言 `currentTime == 800`——唯一能把「并发」变成可回归断言的手段。
- 顺带演示 profile 切换：加 `--spring.profiles.active=database`，同一份业务代码换成 JDBC 适配器。

---

## Demo 2 — Spring Security 与错误契约

```bash
git checkout demo-2
eval "$(./gradlew -q generateDemoTokens)"   # 四个 token 直接注入当前 shell
```

**验证目标**

1. 同一个请求体，**只换 token**，得到四种不同结果；
2. 401/403 与 404 出自**两个不同的出口**；
3. 客户端应基于稳定的 `code` 分支，而不是 `message` 文本。

### 演示步骤

五个请求，除标注外请求体都是 `{"gameId":"G-100","selection":"HOME","stake":100}`。

```bash
# ① 无 token
curl -i -X POST http://localhost:8080/api/v1/bets \
  -H 'Content-Type: application/json' -d '{"gameId":"G-100","selection":"HOME","stake":100}'

# ② 有合法 token，但没有任何 scope
curl -i -X POST http://localhost:8080/api/v1/bets \
  -H "Authorization: Bearer $VALID_NO_SCOPE_TOKEN" \
  -H 'Content-Type: application/json' -d '{"gameId":"G-100","selection":"HOME","stake":100}'

# ③ scope 正确，但赛事不存在（只改 gameId）
curl -i -X POST http://localhost:8080/api/v1/bets \
  -H "Authorization: Bearer $VALID_BETS_WRITE_TOKEN" \
  -H 'Content-Type: application/json' -H 'X-Trace-Id: demo-404' \
  -d '{"gameId":"G-404","selection":"HOME","stake":100}'

# ④ 一切正确
curl -i -X POST http://localhost:8080/api/v1/bets \
  -H "Authorization: Bearer $VALID_BETS_WRITE_TOKEN" \
  -H 'Content-Type: application/json' -d '{"gameId":"G-100","selection":"HOME","stake":100}'

# ⑤ 读取赛事（games:read）
curl -i http://localhost:8080/api/v1/games/G-100 -H "Authorization: Bearer $VALID_GAMES_READ_TOKEN"
```

**期望结果**（已实测）

| # | 场景 | 状态码 | 响应要点 |
|---|---|---|---|
| ① | 无 token | **401** | `code: AUTHENTICATION_REQUIRED` |
| ② | token 无 scope | **403** | `code: INSUFFICIENT_SCOPE` |
| ③ | 赛事不存在 | **404** | `code: GAME_NOT_FOUND`，六字段完整错误契约 |
| ④ | 正常下注 | **201** | `betId` / `odds: 1.85` / `status: ACCEPTED` |
| ⑤ | 读取赛事 | **200** | 队名与双边赔率 |

③ 的完整响应体：

```json
{"timestamp":"...","status":404,"code":"GAME_NOT_FOUND",
 "message":"Game G-404 was not found","path":"/api/v1/bets","traceId":"demo-404"}
```

**讲解要点**

- ① ② 的响应体只有 `{status, code, message}` **三个字段**，③ 有 **六个**——前者由 `SecurityConfig` 里手写的 `authenticationEntryPoint` / `accessDeniedHandler` 产生，请求**根本没到 controller**；后者才走 `@RestControllerAdvice`。
- ③ 只改了 `gameId`，token 完全没动——「**认证通过 ≠ 业务成功**」。
- 授权规则集中在 `SecurityConfig.applyScopeRules()`，一眼读完：`GET /api/v1/games/**` 要 `SCOPE_games:read`，`POST /api/v1/bets` 要 `SCOPE_bets:write`。
- 对比 demo-1：同样请求未知赛事时是 **500**，因为那时还没有异常映射。这一步把「逻辑对了」变成「契约对了」。
- 可顺带提：`anyRequest().permitAll()` 意味着 actuator 目前是开放的，生产应收紧。

---

## Demo 3 — 消息：持久化事件与消费者组

```bash
git checkout demo-3
docker-compose up -d solace
./gradlew bootRun --args='--spring.profiles.active=solace'
```

**验证目标**

1. 一次下注同时产生两个结果：客户端**立刻**拿到 201，事件**异步**到达消费者；
2. 应用代码发给一个 **binding 名**，不是发给 broker API；
3. **consumer group 才是把 topic 订阅变成持久队列的那一行配置**，队列由 binder 自动创建。

> 追踪（一个 traceId 串起全链路）留到 Demo 9 一起演示，这里只看消息本身。

### 第 1 步 — 先看 broker 上有什么

```bash
# Manager: http://localhost:8088，admin/admin
curl -s -u admin:admin http://localhost:8088/SEMP/v2/monitor/msgVpns/default/queues \
  | python3 -m json.tool | grep -E "queueName|msgSpoolUsage"
```

### 第 2 步 — 下一注

```bash
curl -i -X POST http://localhost:8080/api/v1/bets \
  -H "Authorization: Bearer $VALID_BETS_WRITE_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"gameId":"G-100","selection":"HOME","stake":100}'
```

**期望结果**（已实测）：客户端立刻拿到 **201**，终端 B 的日志随后出现两行：

```
ForkJoinPool.commonPool-worker-1            | SolaceBetPlacedPublisher      | event published eventId=bc877e8f… destination=betPlaced-out-0
solace-scst-consumer-notifyBetPlaced-in-01  | NotificationBetPlacedConsumer | event notified eventId=bc877e8f…
```

队列 `scst/wk/notify-bet-placed/plain/sportsbook/bet/placed/v2` 出现并计数。

### 第 3 步 — 读三处代码

| 文件 | 看什么 |
|---|---|
| `messaging/SolaceBetPlacedPublisher.kt` | `StreamBridge.send("betPlaced-out-0", …)` —— 发给 **binding 名**，代码里没有任何 broker API |
| `application-solace.properties` | destination 是 topic；**`group=notify-bet-placed` 这一行**把订阅变成持久队列，队列没有任何代码创建 |
| `configuration/SolaceMessagingConfiguration.kt` | 消费端就是一个 `Consumer<Message<BetPlacedEvent>>` bean，不是监听器框架 |

**讲解要点**

- **消费线程名 `solace-scst-consumer-…` 是决定性证据**：事件是从队列消费的，不是进程内方法调用。
- **发布者是一个端口，两个适配器**：去掉 `solace` profile 重启，同样下注，队列计数**纹丝不动**——走的是进程内的 `InMemoryBetPlacedPublisher`，而业务代码一行没改。这就是课堂能在没有 broker 的机器上跑起来的原因。
- `BetPlacedEvent` 只描述业务事实（`eventId` / `occurredAt` / `data`），**没有诊断字段**。相关性怎么传递，Demo 9 再讲。
- 队列名 `scst/wk/**notify-bet-placed**/plain/**sportsbook/bet/placed/v2**` 把 group 和 topic 都编码在里面——看名字就知道它是谁的、订的什么。

---

## Demo 4 — TDD、单元测试、契约测试

```bash
git checkout demo-4
```

### 4.1 TDD：唯一需要现场写代码的部分

**需求**：单注金额不得超过 1000，超过时返回 `400` + `INVALID_BET`。

起点是全绿。三处注释块，**一次只取消一处**：

```bash
grep -rn "=== Demo TDD" src/ --include=*.kt
```

| 步骤 | 文件 | 动作 | 期望（已实测） |
|---|---|---|---|
| 1 | `test/…/BetControllerTest.kt` | 取消注释新测试 | `Status expected:<400> but was:<201>` |
| 2 | `main/…/service/BetService.kt` | 取消注释 stake 规则 | `Status expected:<400> but was:<500>` |
| 3 | `main/…/exception/ApiExceptionHandler.kt` | 取消注释异常映射 | 全绿 |

```bash
./gradlew test --tests '*BetControllerTest' --rerun-tasks   # 步骤 1、2 之后各跑一次
./gradlew test --rerun-tasks                                 # 步骤 3 之后跑全量
```

**讲解要点**

- **生产代码一行没改，测试就先红了**——红色描述的是「新契约还没实现」。
- **`500` 而不是 `201` 是最好的一页**：规则已经生效（不再接受下注），但异常没被映射，兜底处理器返回 500。**「规则对了」不等于「契约对了」。**
- 规则放在**任何 I/O 之前**——超限的下注不该白白调用两个外部服务。这个设计选择被单元测试的 `coVerify(exactly = 0)` 锁住。
- 一个红色测试驱动了**两处生产代码改动**，在两个不同文件里。

演示后复原：`git checkout -- src/`

### 4.2 单元测试

```bash
./gradlew test --tests '*BetServiceTest' --rerun-tasks
```

**讲解要点**

- **不碰数据库、不碰 broker、不碰网络**：MockK 把两个客户端和仓储全部打桩，毫秒级完成。
- **断言 API 测试看不到的东西**：`coVerify(exactly = 0) { oddsClient.validateSelection(any(), any()) }` ——超限下注**不会去调用**外部服务。HTTP 响应上完全看不出这一点。
- spec 顶层那句 `beforeTest { clearAllMocks() }` 是 F.I.R.S.T. 的 **Isolated**，不是装饰。注释掉它再跑，失败信息会直接点名上一个测试留下的调用：

```
OddsClient(#3).validateSelection(any(), any()) should not be called
Calls: 1) OddsClient(#3).validateSelection(G-404, HOME, continuation {})
```

- 可选：把 `BetService.saveBet` 里的 `stake` 和 `odds` 对调（都是 `BigDecimal`，编译通过、状态码仍是 201），跑全量——单元测试报 `expected:<1.85> but was:<100>`，API 测试报 `JSON path "$.odds"`。**同一个 bug，两层给出的信息价值不同。**

### 4.3 契约测试

```bash
./gradlew contractTest        # 四个测试，完全本地，无需网络与凭据
```

**讲解要点**

- 跑之前 `build/pacts/` **不存在**，跑完才出现——契约是消费者测试通过后的**产物**，不是输入，因此不可能和消费者真实行为脱节。
- 打开 `build/pacts/*.json` 讲 matcher：

| 声明 | 承诺 |
|---|---|
| `stringValue("status", "ACCEPTED")` | 必须精确等于（客户端要基于它分支） |
| `stringType("betId", "B-201")` | 只要是字符串，值随便 |
| `decimalType("odds", 1.85)` | 只要是小数，字符串会失败 |
| 未声明的字段 | 完全忽略 |

  这个不对称就是「**加字段安全、改名危险**」的可执行形式。

- 提供者侧：`PlaceBetHttpProviderPactTest` 用 `@SpringBootTest` 起真实应用重放请求，**穿过安全过滤链、校验、controller、序列化**，不是 stub。
- **破坏演示**：把 `BetPlacedData` 的 `betId` 改名为 `id`，修掉编译错误后重跑 `contractTest`：

```
1.1) body: $.data Actual map is missing the following keys: betId
```

  事件仍是合法 JSON、仍能正常发布、状态码也没变——**只有契约发现了**。改回去即绿。

### 4.4 PactFlow：把契约结果变成部署门禁

前面三步完全本地。这一步把契约推到 broker，让「能不能上线」由**记录在案的校验证据**回答。

**凭据**（课前准备好，token 用 PactFlow → Settings → System Accounts 生成的，不要用个人 token）：

```bash
export PACT_BROKER_BASE_URL=https://<tenant>.pactflow.io
export PACT_BROKER_TOKEN=<read/write token>
export GIT_COMMIT=$(git rev-parse HEAD)
export GIT_BRANCH=$(git branch --show-current)
export PACT_ENVIRONMENT=test        # 可选，默认就是 test
```

> 这四个变量由 Gradle 用 `providers.environmentVariable` 读取，**不是** `System.getenv`——
> 后者读的是 Gradle 守护进程启动时的环境，当前 shell 里 export 的值会被忽略。这本身就是个
> 值得一提的坑。

**四条命令，故意按这个顺序跑**：

```bash
./gradlew contractTest        # ① 本地生成契约，不联网
./gradlew pactPublish         # ② 连同 commit sha 和分支名推到 PactFlow
./gradlew canIDeploy          # ③ 故意先问一次
./gradlew pactBrokerVerify    # ④ 从 broker 拉取契约校验，并把结果回传
./gradlew canIDeploy          # ⑤ 再问一次
```

| 命令 | 做什么 |
|---|---|
| `contractTest` | 本地跑消费者 + 提供者测试，产出 `build/pacts/*.json`，**不联网** |
| `pactPublish` | 把契约推到 PactFlow，版本号是 commit sha |
| `pactBrokerVerify` | 从 PactFlow **拉取**契约做提供者校验，并把结果**回传**（`pact.verifier.publishResults=true`） |
| `canIDeploy` | 查 Matrix：这个版本的 `betting-api`，对目标环境里的消费者是否都有通过的校验记录 |

**期望结果**（已实测，先红后绿是本节的高光）

`pactPublish` 之后：

```
✅ Created betting-training-consumer version <sha> with branch <branch>
✅ Created bet-notification-service version <sha> with branch <branch>
```

第一次 `canIDeploy`：

```
No pacts or verifications have been published for version <sha> of betting-api
❌ Computer says no ¯\_(ツ)_/¯
```

`pactBrokerVerify` 之后再问：

```
✅ Computer says yes \o/
```

**讲解要点**

- **中间代码一行没改**，变的只是 Matrix 里有没有校验证据。「没有校验记录 ≠ 通过」不用讲道理，跑一遍就懂。
- 为什么必须有 `pactBrokerVerify`：`contractTest` 的本地校验**不会在 PactFlow 留下任何记录**，只有从 broker 拉取的校验才能回传结果。这也是 `BrokerVerificationPactTest` 单独成类的原因——Pact 不允许一个测试类同时带 `@PactFolder` 和 `@PactBroker`。
- 版本号必须是**不可变的 commit sha**。`latest` 不是版本，Matrix 无法对它做推理。
- 界面上可以看到 **一个 provider、两个 consumer、两种交互方式**（HTTP 和消息）。

**⚠️ 现场注意**

- 建议用**培训专用 tenant**，课堂会往里写入 `betting-api` 等 pacticipant。
- **网络不通时的退路**：只演 4.1 – 4.3（完全本地），4.4 改为截图讲解。

---

## Demo 5 — Gradle 构建结构与依赖管理

任意分支，打开 `build.gradle.kts` 从上往下读，每一段回答一个问题：

| 区块 | 回答什么 |
|---|---|
| `plugins` | 这个构建**能做什么**（Kotlin、Spring Boot、依赖管理） |
| `dependencyManagement` / BOM | 版本从哪来——**BOM 统一版本，声明处不写版本号** |
| `dependencies` | 应用**运行**需要什么，测试需要什么，运行时才需要什么（`runtimeOnly`） |
| `sourceSets` | `test` / `integrationTest` / `contractTest` / `demo` 各自的**成本与用途** |
| `tasks` | 团队期望如何变成**可执行的证据** |

```bash
./gradlew tasks --group=verification
./gradlew dependencies --configuration runtimeClasspath | head -40
```

**讲解要点**

- **声明的版本 ≠ 解析出来的版本**：BOM 会把传递依赖统一到一致的版本，`dependencies` 报告才是真相。
- `runtimeOnly` 与 `implementation` 的区别：驱动、注册器不需要出现在编译期。仓库里有三个现成例子（PostgreSQL 驱动、Prometheus 注册器、`flyway-database-postgresql`）。
- **测试按成本分家**：`test` 不需要 Docker，`integrationTest` 需要，`contractTest` 两者都不需要但要 Pact。放在一起就只能整体慢。
- `demo` source set 编译得到、Gradle 跑得起来，却**不进生产制品**——下一节验证这一点。

---

## Demo 6 — 四种打包形态

```bash
./gradlew bootJar jar thinJar slimDist     # jar 产出 -plain.jar，bootJar 不会
ls -lh build/libs/
```

**期望结果**（已实测）

| 产物 | 大小 | 形态 |
|---|---|---|
| `basketball-betting-0.0.1-SNAPSHOT.jar` | **59 M** | executable / dependency-inclusive：`java -jar` 直接跑，依赖全在里面 |
| `basketball-betting-0.0.1-SNAPSHOT-plain.jar` | 103 K | plain：只有应用类 |
| `basketball-betting.jar` | 103 K | thin：同上，但由 `thinJar` 任务显式产出 |
| `build/slim/basketball-betting/` | app + lib(149 个 jar) + bin | slim 发行包 |

```bash
cat build/slim/basketball-betting/bin/basketball-betting
build/slim/basketball-betting/bin/basketball-betting          # 实测可启动
```

**讲解要点**

- **发行物就是运行时契约**：slim 包里的启动脚本写死了 classpath 顺序，`app/` 和 `lib/` 分开，意味着改一行业务代码只需重新分发 103 K，而不是 59 M。
- 反过来，executable jar 的价值是**一个文件、零外部依赖**，适合容器镜像和 `java -jar`。
- 一个可部署单元 **不只是一个 jar**：它包含启动命令、配置和依赖布局。
- 验证 demo 代码没混进生产制品：

```bash
unzip -l build/libs/basketball-betting.jar | grep -c "betting/demo/"   # 期望 0
```

---

## Demo 7 — Flyway 迁移

```bash
docker-compose up -d postgres
```

**验证目标**：schema 变更是代码；**已经跑过的迁移不可修改**，Flyway 用 checksum 把这条规则变成**启动失败**。

### 第 1 步 — 看当前状态

```bash
docker exec betting-postgres psql -U betting -d betting \
  -c "select version, description, success from flyway_schema_history order by installed_rank;"
```

### 第 2 步 — 加一个迁移（正确做法）

```bash
mv src/main/resources/db/migration/V4__index_bet_by_game.sql.disabled \
   src/main/resources/db/migration/V4__index_bet_by_game.sql
./gradlew bootRun --args='--spring.profiles.active=database'
```

期望日志（已实测）：

```
o.f.core.internal.command.DbValidate |Successfully validated 4 migrations
o.f.core.internal.command.DbMigrate  |Current version of schema "public": 3
o.f.core.internal.command.DbMigrate  |Migrating schema "public" to version "4 - index bet by game"
o.f.core.internal.command.DbMigrate  |Successfully applied 1 migration to schema "public", now at version v4
```

### 第 3 步 — 改一个已应用的迁移（错误做法，重点）

停掉应用，往 **V2** 末尾追加一行 `create index ...`，重启：

```
Validate failed: Migrations have failed validation
Migration checksum mismatch for migration version 2
-> Applied to database : 1477632107
-> Resolved locally    : 1223428542
Either revert the changes to the migration, or run repair to update the schema history.
```

### 第 4 步 — 复原

```bash
git checkout -- src/main/resources/db/migration/
mv src/main/resources/db/migration/V4__index_bet_by_game.sql \
   src/main/resources/db/migration/V4__index_bet_by_game.sql.disabled
docker exec betting-postgres psql -U betting -d betting \
  -c "delete from flyway_schema_history where version='4'; drop index if exists idx_bet_game_id;"
```

**讲解要点**

- **失败点在启动，不在运行时**：错误抛在 `flywayInitializer` 上，`jdbcClient` 依赖它，于是 `betController → betService → jdbcBetRepository → jdbcClient` 整条链创建失败。**坏的 schema 变更根本起不来，也就不可能带着错误结构对外服务。**
- checksum 比对的是文件内容，两个数字并排打出来，非常直观。
- 想改结构，正确做法是**再加一个版本**，而不是回头改 V2——V2 已经在所有环境跑过了。
- `repair` 存在，但那是把历史表改成迁就本地文件，**只在明确知道后果时用**。

---

## Demo 8 — Spring Profile 选择适配器

**验证目标**：同一份业务代码，靠 profile 换掉数据库、消息中间件和外部客户端。

```bash
grep -rn "@Profile" src/main/kotlin | sed 's|src/main/kotlin/com/hkjc/training/betting/||'
```

| 注解 | 生效实现 |
|---|---|
| `@Profile("database")` / `@Profile("!database")` | `JdbcBetRepository`、`JdbcGameRepository` / 两个内存实现 |
| `@Profile("solace")` / `@Profile("!solace")` | `SolaceBetPlacedPublisher` / `InMemoryBetPlacedPublisher` |
| `@Profile("test")` / `@Profile("!test")` | 确定性桩客户端 / 真实 `HttpOddsClient`、`HttpRiskClient` |

```bash
./gradlew bootRun                                                  # 全内存
./gradlew bootRun --args='--spring.profiles.active=database'       # 换数据库
./gradlew bootRun --args='--spring.profiles.active=database,solace' # 两个真实适配器
```

**讲解要点**

- Profile **可组合**，不是互斥的模式开关。
- 端口与适配器：接口定义在 `repository/` 和 `messaging/`，实现挨着它，选谁由配置决定——**业务代码不知道自己在跟谁说话**。
- 这就是课堂能在没有 Docker 的机器上跑起来的原因，也是集成测试能只针对数据库边界的原因。
- 环境相关的配置同理：`application-database.properties`、`application-solace.properties`、`application-observability.properties` 各管一段。

---

## Demo 9 — 用证据解释一次下注

**建议在 `demo-3` 或 `demo-4` 上跑**，这样链路里有 broker，追踪才跨得过去：

```bash
docker-compose up -d
./gradlew bootRun --args='--spring.profiles.active=database,solace'
```

**验证目标**

1. 日志、指标、追踪各自回答什么问题，什么时候该用哪个；
2. **一个稳定标识**如何把 API、数据库、broker 三处证据连起来；
3. 指标的维度为什么必须有界。

### 第 1 步 — 发一次请求，带上自己的 traceId

```bash
curl -i -X POST http://localhost:8080/api/v1/bets \
  -H "Authorization: Bearer $VALID_BETS_WRITE_TOKEN" \
  -H 'Content-Type: application/json' -H 'X-Trace-Id: demo9-check' \
  -d '{"gameId":"G-100","selection":"HOME","stake":100}'
```

响应头里会原样回显 `X-Trace-Id: demo9-check`——**客户端拿到的和日志里的是同一个值**，
这是排障时第一手的线索。不传这个头也可以，服务端会生成一个 UUID 并同样回显。

### 第 2 步 — 用它串起四个阶段

在终端 B 搜 `demo9-check`，**期望结果**（已实测）：

```
http-nio-8080-exec-1                        |demo9-check|TraceIdFilter                  |request received method=POST path=/api/v1/bets
ForkJoinPool.commonPool-worker-1            |demo9-check|BetService                     |bet accepted betId=B-1 gameId=G-100 …
ForkJoinPool.commonPool-worker-1            |demo9-check|SolaceBetPlacedPublisher       |event published eventId=bc877e8f… destination=betPlaced-out-0
solace-scst-consumer-notifyBetPlaced-in-01  |demo9-check|NotificationBetPlacedConsumer  |event notified eventId=bc877e8f…
```

**四行、三个不同线程、跨了一次 broker，traceId 完全一致。**

这四行是怎么做到的，值得逐个交代：

| 机制 | 在哪 | 解决什么 |
|---|---|---|
| `TraceIdFilter` 生成/读取并放进 MDC | `configuration/` | 让日志框架能取到它，**业务代码不传这个参数** |
| `logging.pattern.console` 固定六列 | `application.properties` | `@timestamp \| level \| thread_name \| trace_id \| logger_name \| message` |
| `@Order(HIGHEST_PRECEDENCE)` | 过滤器上 | 排在安全过滤链**之前**，所以连 401 都是可关联的 |
| `withContext(MDCContext())` | `BetService.placeBet` | 协程切换线程时把 MDC 带过去（第 2、3 行换了线程仍有 traceId） |
| 消息头 + `withTraceId(...)` | `SolaceBetPlacedPublisher` / `SolaceMessagingConfiguration` | **跨 broker**：发送方挂在消息头上，消费方取出来重新放进 MDC |

**讲解要点**

- **相关性走传输层，不进业务载荷。** 翻开 `BetPlacedEvent` 确认它**没有 `traceId` 字段**——事件描述业务事实，诊断元数据属于消息头。换一套追踪方案（比如 W3C `traceparent`）不需要改事件契约。
- 第 4 行的线程名 `solace-scst-consumer-…` 证明它是从队列消费的；MDC 是**线程级**的，所以那边必须显式重建，不会自己传过去。

### 第 3 步 — 指标

```bash
curl -s http://localhost:8080/actuator/metrics | python3 -m json.tool | head
curl -s http://localhost:8080/actuator/metrics/http.server.requests | python3 -m json.tool
curl -s 'http://localhost:8080/actuator/metrics/http.server.requests?tag=uri:/api/v1/bets'
curl -s http://localhost:8080/actuator/prometheus | head -20
```

**讲解要点**

- `/actuator/metrics` 是**索引**，只列名字；值在下一层，`measurements` 才是数字，`?tag=` 可以下钻。
- `http.server.requests` 是**懒注册**的——第一个请求处理完才出现。刚启动就查看不到，不是坏了。
- **`uri` 标签是路由模板不是实际路径**（`/api/v1/games/{gameId}`）。按真实路径打标签会让基数爆炸——这就是「metrics 要用有界维度」的具体含义。
- 未带 token 的请求在指标里是 `uri=UNKNOWN`、`status=401`：请求**在路由匹配之前**就被安全过滤链拦掉了，Spring 还不知道它要去哪个 handler。这从另一个角度佐证了 Demo 2 的结论。
- `/actuator/prometheus` 一次吐出所有指标的当前值，标签写在花括号里——这是给采集器看的形态，和 `/metrics/{name}` 一次一个形成对比。

### 第 4 步（可选）— 结构化日志

加 `observability` profile 可切换成 JSON 日志（`logging.structured.format.console=logstash`），
适合演示「日志给机器看」的形态。但**它会覆盖上面那个六列格式**，讲追踪时不要开。

---

## 附：命令速查

```bash
# 环境
docker-compose up -d
./gradlew runDemoDownstreams                                        # 终端 A
./gradlew bootRun --args='--spring.profiles.active=database,solace' # 终端 B
eval "$(./gradlew -q generateDemoTokens)"                            # demo-2 起需要

# 测试
./gradlew test --rerun-tasks
./gradlew integrationTest        # 需要 Docker
./gradlew contractTest           # demo-4

# PactFlow（需要 PACT_BROKER_BASE_URL / PACT_BROKER_TOKEN / GIT_COMMIT / GIT_BRANCH）
./gradlew pactPublish
./gradlew canIDeploy             # 先问：❌ Computer says no
./gradlew pactBrokerVerify
./gradlew canIDeploy             # 再问：✅ Computer says yes

# 打包
./gradlew bootJar thinJar slimDist
build/slim/basketball-betting/bin/basketball-betting

# 观察点
http://localhost:8080/swagger-ui.html
http://localhost:8080/actuator/health
http://localhost:8080/actuator/metrics/http.server.requests
http://localhost:8080/actuator/prometheus
http://localhost:8088                    # Solace Manager（admin/admin）

# 演示后复原
git checkout -- src/
grep -rn "=== Demo TDD" src/ --include=*.kt      # demo-4 上应有 3 处
ls src/main/resources/db/migration/               # V4 应是 .sql.disabled
```
