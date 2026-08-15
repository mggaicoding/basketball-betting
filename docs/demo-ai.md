# AI Session Demo 清单

分支 `demo-ai`，基于 `main`。五个 Demo 分两部分：前四个介绍 GitHub Copilot 的用法，
第五个用 Spec-Driven Development 完整走一遍需求 → 任务 → 代码。

| Demo | 主题 | 现场动作 |
|---|---|---|
| 1 | 注释即提示：inline 补全实现与测试 | Copilot 写代码 |
| 2 | 用 Copilot 实现新 API：`GET /api/v1/bets/{betId}` | Copilot 写代码 |
| 3 | 用 Copilot 为这个 API 生成测试 | Copilot 写测试 |
| 4 | 用 Copilot 生成 GitHub Actions 工作流 | Copilot 写 YAML |
| 5 | OpenSpec 驱动「分享我的下注」 | 带学员练习 |

> **贯穿全场的一句话**：Copilot 生成的东西**看起来总是对的**。每个 Demo 都要留出
> 「它漏了什么」的环节——这比它写对了什么更有教学价值。

---

## 课前准备

```bash
git checkout demo-ai
./gradlew test                    # 预热，应全绿
docker-compose up -d              # Demo 2 想演示数据库时才需要
```

**IDE 侧**：装好 GitHub Copilot 插件并登录；确认 Copilot Chat 可用。

**已经为你准备好的前置内容**（都在本分支上）：

| 文件 | 作用 |
|---|---|
| `.github/copilot-instructions.md` | 仓库级自定义指令。Copilot 会读它，因此建议贴合本仓库的分层、命名、测试约定 |
| `src/main/kotlin/.../service/PayoutCalculator.kt` | Demo 1 的起点：KDoc 讲业务来由，行注释即提示词，函数体是 `TODO()` |
| `src/test/kotlin/.../PayoutCalculatorTest.kt` | Demo 1 的测试起点：一个占位测试保证 spec 可运行，真正要写的用例以注释列出 |
| `openspec/` + `.github/prompts/opsx-*.prompt.md` | Demo 5 的 OpenSpec 脚手架，已按 `--tools github-copilot` 初始化 |
| `Bet.customerId` + 迁移 `V5` | Demo 5 的前置：没有归属就谈不上「分享我的下注」 |

---

## Demo 1 — 注释即提示词，inline 补全代码与测试

**业务背景**：下注确认页要告诉客户「若中奖可得多少」。现在 `POST /api/v1/bets` 只返回
`betId / odds / status`，客户端只能自己拿 stake 乘 odds——**而怎么取整、单注上限是多少，
是庄家的决定，不是客户端的决定**。放任每个客户端自己算，迟早出现两个端对同一笔下注显示
两个金额。所以这个计算要落在服务端。

上限 1,000,000 是博彩业真实存在的单注派彩上限。

> 本 Demo **不要求现场写完**。跑通「一条规则 → 一段实现 → 一个测试」这个循环即可，
> 剩下的用例留给学员自己补。

**目标**：让学员看到最基础也最常用的一种用法——把要求写清楚，代码自己长出来；
并立刻看到它的边界：**注释里没写的规则，它不会替你想**。

### 第 1 步 — 让 inline 补全写实现

打开 `service/PayoutCalculator.kt`。KDoc 说明了为什么要有这个类，下面的行注释就是提示词：

```kotlin
// Return what this bet pays out if it wins.
//
// - The payout is stake * odds, rounded HALF_UP to 2 decimal places, because the
//   customer is shown a money amount and money has two decimals.
// - Stake and odds must both be greater than zero; reject anything else with
//   IllegalArgumentException, naming which argument was wrong.
// - A single bet may not pay out more than 1,000,000. Reject a bet that would
//   exceed it rather than silently capping the number, because a capped payout is
//   a promise the house never made.
fun potentialPayout(stake: BigDecimal, odds: BigDecimal): BigDecimal = TODO(...)
```

删掉 `= TODO(...)`，换成 `{`，回车。**等 inline 建议出现，`Tab` 接受。**

**期望结果**：三条规则都被实现——`multiply` + `setScale(2, RoundingMode.HALF_UP)`、
两个 `require`、上限检查。

### 第 2 步 — 用 inline 补全写功能测试

打开 `src/test/kotlin/com/hkjc/training/betting/PayoutCalculatorTest.kt`。

里面只有一个占位测试（`calculator shouldBe calculator`），**它什么都没验证**，存在的意义
只是让这个 spec 在演示开始前可以运行。真正要写的用例以注释列在下面：

```kotlin
// - a winning bet returns stake * odds
// - the amount is rounded to two decimals, HALF_UP
// - a stake of zero or less is rejected, and the message names the stake
// - odds of zero or less are rejected, and the message names the odds
// - a payout over 1,000,000 is rejected rather than capped
```

**只打测试名，让它补全函数体**：

```kotlin
test("a winning bet returns stake times odds") {
```

回车后等建议。写完两三个用例后**删掉占位测试**。

```bash
./gradlew test --tests '*PayoutCalculatorTest' --rerun-tasks
```

**期望结果**：全绿。若 Copilot 的实现漏了某条规则，对应那个测试会红——**这正是要看的**：
生成的代码得靠测试来证明，不能靠它看起来对。

### 讲解要点

- **好提示词的三个特征**，注释里都有：说清规则、说清边界、**说清为什么**。最后一条
  最容易被忽略——「capped payout is a promise the house never made」这句话让它选择
  抛异常而不是截断。去掉这句再试一次，它多半会写成 `min(payout, MAX)`。
- **对照实验**：把注释删到只剩第一行再让它生成——出来的版本没有校验、没有上限。
  同一个模型，输入决定输出。
- **仓库级指令在起作用**：不加约束时 Copilot 默认写 JUnit + Mockito；这里它写 Kotest，
  是因为 `.github/copilot-instructions.md` 里写了这条约定。**一次配置，全仓库受益。**
- **测试是唯一的验收手段**：实现是生成的，正确性只能由测试证明。这也是为什么第 2 步
  要自己写用例名——**意图必须由人给出**，让它连用例一起想，就没有独立的验收了。
- 想强调红绿循环的话，把两步对调：先写功能测试（此时实现还是 `TODO()`，会红成
  `NotImplementedError`），再让 Copilot 补实现转绿。

---

## Demo 2 — 用 Copilot 实现一个新 API

**目标**：从「补全一个函数」升级到「跨文件完成一个功能」，并看清 Copilot 在**架构约束**
上会漏什么。

**需求**：`GET /api/v1/bets/{betId}`，返回一笔下注的详情。

### 第 1 步 — 先让学员猜要改几个文件

这是全场最好的一个提问。多数人答「controller 加个方法」。实际上：

| # | 文件 | 改什么 |
|---|---|---|
| 1 | `repository/BetRepository.kt` | 端口加 `findById(betId): Bet?` |
| 2 | `repository/InMemoryBetRepository.kt` | 已经在存了，加一行读取 |
| 3 | `repository/JdbcBetRepository.kt` | 加 SELECT 和行映射 |
| 4 | `service/BetQueryService.kt` | 新建；查不到抛领域异常 |
| 5 | `exception/BetNotFoundException.kt` | 新建 |
| 6 | `exception/ApiExceptionHandler.kt` | 映射成 404 `BET_NOT_FOUND` |
| 7 | `dto/BetDetailResponse.kt` | 新建；`BetResponse` 只有 betId/odds/status，不够 |
| 8 | `controller/BetController.kt` | `@GetMapping("/{betId}")` |
| 9 | **`configuration/SecurityConfig.kt`** | **加授权规则** |

### 第 2 步 — 交给 Copilot

Copilot Chat（Agent 模式效果最好）：

```
Add GET /api/v1/bets/{betId} returning the bet's details.
Follow the repository conventions in .github/copilot-instructions.md.
```

### 第 3 步 — 逐条对答案（重点）

**期望结果**：前 8 项通常能写对，尤其在仓库指令的约束下会自觉分 DTO、抛领域异常。

**第 9 项是关键**。`SecurityConfig` 的规则链以 `anyRequest().permitAll()` 收尾，
所以**漏掉授权规则的新路由是完全开放的**——任何人不带 token 就能读到别人的下注。

现场验证（不带 token）：

```bash
# 先下一注拿到 betId
eval "$(./gradlew -q generateDemoTokens)"
BET=$(curl -s -X POST http://localhost:8080/api/v1/bets \
  -H "Authorization: Bearer $VALID_BETS_WRITE_TOKEN" -H 'Content-Type: application/json' \
  -d '{"gameId":"G-100","selection":"HOME","stake":100}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["betId"])')

# 不带任何 token 去读它
curl -i http://localhost:8080/api/v1/bets/$BET
```

漏了规则就是 **200**，加上规则才是 **401**。

### 讲解要点

- **编译通过、测试通过、状态码正常，安全洞照样在。** 这类缺陷没有任何自动信号，
  只有人知道「这个仓库的规则链是 permitAll 收尾」。
- **仓库指令能把这条知识喂给它**：本分支的 `.github/copilot-instructions.md` 里有一节
  「Adding a route」，明确列了包括 SecurityConfig 在内的六项。可以现场对比——把那一节
  临时删掉再问一次，看它是否还记得。
- 第二个容易漏的：**这笔下注是谁的？** `Bet` 现在有 `customerId`，那么 A 能不能读到 B 的
  下注？Copilot 不会主动问这个问题。这正好接到 Demo 5 的话题。

---

## Demo 3 — 让 Copilot 为新 API 生成测试

**目标**：测试是 Copilot 最擅长、投入产出比最高的场景；同时看清「覆盖了路径」不等于
「覆盖了风险」。

### 第 1 步 — 生成

选中 `BetController`，Copilot Chat：

```
Write API tests for GET /api/v1/bets/{betId}, following the test conventions in this repository.
```

**期望结果**：`@SpringBootTest` + `@AutoConfigureMockMvc`，覆盖「存在 → 200」「不存在 →
404 且 code 为 BET_NOT_FOUND」。

### 第 2 步 — 数它没写什么

对照一份完整清单，逐条问「它写了吗」：

| 应有的用例 | Copilot 通常 |
|---|---|
| 存在的 betId → 200 + 字段 | ✅ 会写 |
| 不存在 → 404 + 稳定 code | ✅ 会写 |
| 不带 token → 401 | ⚠️ 时有时无 |
| token 没有对应 scope → 403 | ❌ 基本不写 |
| **读别人的下注** → 应当拒绝 | ❌ 不会写——它不知道这是个需求 |
| 响应里**不能**出现 customerId | ❌ 不会写 |

### 讲解要点

- **它测的是你写的代码，不是你该写的代码。** 最后两行是需求层面的要求，代码里没有，
  它就测不出来——**AI 不会替你发现缺失的需求**。
- 顺带回扣后端 Demo 4 的 TDD：**先写测试**时，测试描述的是意图；**后补测试**时，测试
  描述的是实现。让 Copilot 补测试属于后者，用它加密覆盖率可以，用它保证正确性不行。
- 一个稳妥的用法：**自己写用例名，让 Copilot 填实现**。意图仍由人掌握。

---

## Demo 4 — 让 Copilot 生成 GitHub Actions

**目标**：跳出应用代码，演示 Copilot 在**配置类文件**上的能力，以及为什么这类产物
更需要人来复核——它跑在别人的机器上，本地看不出问题。

### 第 1 步 — 生成

仓库里目前没有 `.github/workflows/`。Copilot Chat：

```
Create a GitHub Actions workflow that builds this project and runs its tests on every
push and pull request.
```

### 第 2 步 — 对着真实构建复核

**这个仓库的正确答案长这样**（拿它当对照，不要直接给学员）：

```yaml
name: build
on:
  push:
  pull_request:

jobs:
  verify:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '17'          # 项目锁定 17，不是 21
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew test
      - run: ./gradlew integrationTest    # Testcontainers，runner 自带 Docker
      - run: ./gradlew contractTest       # 不需要网络，也不需要 PactFlow 账号
```

逐条核对 Copilot 的产出：

| 检查项 | 为什么会错 |
|---|---|
| JDK 版本是 **17** | 它常写 21 或 latest；本项目 `sourceCompatibility = 17` |
| 跑了 `integrationTest` 吗 | 它通常只跑 `./gradlew build`，而 `check` **没有**挂 integrationTest（见 build.gradle.kts 里 `tasks.check` 那段注释） |
| 跑了 `contractTest` 吗 | 同上，它是独立 source set，不在默认生命周期里 |
| 有没有臆造 service 容器 | 集成测试用 Testcontainers **自带** PostgreSQL，额外起 `services:` 是多余的 |
| 有没有引用不存在的 secrets | PactFlow 那套需要 token，`contractTest` 不需要——它有时会一起写进去 |
| action 版本 | 老模型会给 `actions/checkout@v2` |

### 讲解要点

- **本地没有反馈回路。** 应用代码写错了编译器会说话；workflow 写错了要 push 上去才知道，
  这正是需要人先读一遍的原因。
- **它不知道你的构建有非标准 source set。** `integrationTest` 和 `contractTest` 是这个
  仓库特有的，不在 `check` 里——这类项目特定知识只能靠人补，或者写进仓库指令。
- 生成后**不要直接提交**：先本地 `./gradlew test integrationTest contractTest` 跑通，
  确认命令本身是对的。

---

## Demo 5 — Spec-Driven Development：分享我的下注

> **可以带学员一起做。** 这一部分的重点不是写得多快，而是**在写之前把问题问清楚**。

**需求（原始表述，故意含糊）**：

> 用户可以把自己的下注信息分享给其他人。

只做后端。

### 为什么要用 SDD

把上面那句话直接丢给 Copilot，它会给你一个能跑的东西——但它替你做了七八个你没意识到的
决定：分享给谁、对方要不要登录、链接会不会过期、能不能撤销、对方看得到金额吗、看得到
你是谁吗。**这些决定一旦写进代码就很难改回来，而它们本该是产品决定，不是编码决定。**

OpenSpec 的作用就是把这些决定**提前变成文字**，并且是可评审的文字。

### 第 0 步 — 环境已就绪

本分支已经初始化好（`--tools github-copilot`）：

```
openspec/config.yaml                       ← 已填入本项目上下文与产出规则
.github/prompts/opsx-propose.prompt.md
.github/prompts/opsx-apply.prompt.md
.github/prompts/opsx-archive.prompt.md
.github/prompts/opsx-explore.prompt.md
.github/skills/openspec-*/SKILL.md
```

**注意命令写法**：官方文档写作 `/opsx:propose`，但**在 GitHub Copilot 里是连字符**：
`/opsx-propose`。装好后需要**重启 IDE** 命令才会出现。

```bash
npx @fission-ai/openspec list      # 应输出 No active changes found
```

### 第 1 步 — 提案（`/opsx-propose`）

```
/opsx-propose 用户可以把自己的下注信息分享给其他人，只做后端
```

它会在 `openspec/changes/<change-name>/` 下生成 `proposal.md`、`specs/`、`design.md`、
`tasks.md`，**并且停在这里不写代码**（prompt 里明确写了 planning boundary）。

### 第 2 步 — 评审提案（本 Demo 的核心，别跳过）

带学员逐条读 `proposal.md`，重点看**它替我们做了哪些决定**。用这几个问题挑战它：

| 问题 | 为什么重要 |
|---|---|
| 分享给「谁」——公开链接，还是指定某个客户？ | 决定要不要有收件人模型 |
| 对方需要登录吗？ | 决定这条路由在 `SecurityConfig` 里怎么写 |
| 链接会过期吗？能撤销吗？ | 决定要不要存过期时间和状态 |
| 对方能看到金额吗？能看到下注人是谁吗？ | **决定响应 DTO 的字段——这是隐私决定** |
| 同一笔下注能分享多次吗？ | 决定 share 是不是独立实体 |
| 别人的下注我能分享吗？ | 决定授权检查放在哪一层 |

`openspec/config.yaml` 里已经写了规则，要求提案必须有 **Non-goals** 一节、必须列出
open questions，所以它大概率会主动列一部分——**没列全的那些就是现场的价值所在**。

改完提案让它重新生成 `design.md` 和 `tasks.md`。

### 第 3 步 — 实现（`/opsx-apply`）

```
/opsx-apply
```

它按 `tasks.md` 一项项实现。参考的完整改动面（用来核对，不要提前给）：

- 迁移 `V6__create_bet_share.sql`：share 表（id、bet_id、创建时间、过期时间、撤销标记）
- `domain/BetShare.kt`、`repository/BetShareRepository.kt` + 两个适配器
- `service/BetShareService.kt`：**只有下注人本人能分享**
- `controller/BetShareController.kt`：`POST /api/v1/bets/{betId}/shares`、
  `GET /api/v1/shares/{shareId}`
- `dto/SharedBetResponse.kt`：**不含 customerId**
- `exception/`：新异常 + 映射
- **`SecurityConfig`**：创建分享需要 `bets:write`；读取分享按提案的结论决定是否放行
- 测试：服务层 + API 层

### 第 4 步 — 归档（`/opsx-archive`）

change 挪进 `openspec/archive/`，spec 合并进 `openspec/specs/`——**下一个改动的
AI 就能读到这次的结论**，规格随代码一起演进。

### 讲解要点

- **SDD 改变的不是速度，是顺序。** 含糊的需求 + 强大的生成器 = 快速产出一个错误的东西。
  提案环节的每一个问题，都是一次「现在花两分钟，还是上线后花两周」的选择。
- **规格进了仓库就是活的**。归档后的 spec 和代码一起被 review、一起演进，不像
  Confluence 上那份写完就过期的文档。
- **planning boundary 是这套流程的关键设计**：propose 阶段明令不许写代码。这道闸门
  逼着人在评审提案时集中注意力，而不是一边看代码一边糊弄过去。
- 回扣 Demo 2 和 3：那两个 Demo 里 Copilot 漏掉的授权规则和隐私字段，在这里是
  **提案阶段就该被问出来的问题**——工具没变，变的是流程。

---

## 附：命令速查

```bash
# 分支
git checkout demo-ai

# Demo 1
./gradlew test --tests '*PayoutCalculatorTest' --rerun-tasks

# Demo 2 现场验证（授权规则漏没漏）
eval "$(./gradlew -q generateDemoTokens)"
curl -i http://localhost:8080/api/v1/bets/<betId>          # 无 token：应 401

# Demo 4 生成后本地先跑通
./gradlew test integrationTest contractTest

# Demo 5
npx @fission-ai/openspec list
npx @fission-ai/openspec validate --all
#   Copilot 里：/opsx-propose  →  /opsx-apply  →  /opsx-archive

# 演示后复原
git checkout -- src/ && git clean -fd openspec/changes .github/workflows
```
