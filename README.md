# HumanVerify

[![Build and Release](https://github.com/LetSeries/HumanVerify/actions/workflows/build-release.yml/badge.svg)](https://github.com/LetSeries/HumanVerify/actions/workflows/build-release.yml)
[![Latest Release](https://img.shields.io/github/v/release/LetSeries/HumanVerify)](https://github.com/LetSeries/HumanVerify/releases)

一个面向高版本 Minecraft 服务端的游戏内人机验证插件，支持 **Paper、Folia、Purpur**。

## 项目状态

- 版本号跟随每次 `main` 分支提交自动递增并发布 GitHub Release（详见 [Releases](https://github.com/LetSeries/HumanVerify/releases)，请使用最新 Release 的 JAR）。
- 当前构建状态：`mvn clean verify` 已通过（含单元测试）。
- 项目**没有使用** [CubeX-MC/Plugins](https://github.com/CubeX-MC/Plugins) 框架。
- 本项目是独立 Maven 项目，直接依赖 Paper API；没有 CubeX 的 Gradle Kotlin DSL、`buildSrc`、`cubex-*` 模块、`CubeXLib` 或 Shadow 打包配置。

## 功能

- 玩家进入服务器后自动打开验证界面。
- 9 至 54 格背包（9 的倍数，`challenge-size` 可配，默认 27 格）中随机放置目标方块，玩家点击正确方块即可通过。
- 支持验证超时与错误次数限制。
- 支持多种验证方式：唯一颜色方块、唯一材质方块、按编号顺序点击方块、点击指定数量目标方块、找出唯一不同方块、点击中心或角落方块、算术题、倒序点击、整行点击、记忆复述、反应测试；可固定模式或随机选择。
- **验证期间冻结**：未验证玩家无法移动、交互、挖掘、放置、攻击、受伤、丢弃物品、聊天、使用指令（均可独立配置开关）。
- **失败/超时动作可配**：验证失败或超时后自动重开新验证（`RETRY`，默认）或踢出服务器（`KICK`），踢出消息可自定义。
- **难度进阶**：连续失败会自动提升后续验证难度（题目变长、超时变短），成功通过后清零。
- **反脚本**：滑动窗口内刷点击会消耗尝试次数；自助验证有冷却；同 IP 并发待验证人数受限。
- 通过 Bukkit `ServicesManager` 暴露公共 API，其他插件无需依赖实现包即可调用。
- 使用 Paper/Folia `EntityScheduler`，不依赖传统全局调度器，兼容 Folia 区域线程模型。
- 提供 `/humanverify verify`、`/humanverify verify <玩家>`、`/humanverify reload`（权限见下表）。

管理员执行 `/humanverify verify <玩家>` 会为目标玩家打开一次真实验证，不会直接将其标记为已验证；如需直接放行其他插件，可调用 API 的 `markVerified`。

## 命令与权限

| 命令 | 所需权限 | 默认 | 说明 |
|------|----------|------|------|
| `/humanverify`、`/humanverify verify` | `humanverify.use` | `true`（所有人） | 为自己打开验证；已有未完成验证时复用同一界面，不重置错误次数；受 `verify-cooldown-seconds` 冷却限制 |
| `/humanverify verify <玩家>` | `humanverify.admin` | `op` | 为目标玩家强制重开验证（`force`，会重置其当前题目）；玩家名大小写不敏感 |
| `/humanverify reload` | `humanverify.admin` | `op` | 重载配置并补写新增默认项 |
| （自动验证） | `humanverify.bypass` | `op` | 拥有此权限的玩家进服不弹验证，API 直接返回 `SUCCESS` |

命令别名：`/hv`、`/captcha`。无权限时会显示中文提示。

## 运行环境

- Java 21 或更高版本。
- Paper API `1.21.8-R0.1-SNAPSHOT`，因此实际服务端应使用兼容的 Minecraft 1.21.x 版本。
- `plugin.yml` 已声明 `folia-supported: true`；Purpur 通常兼容 Paper API，但仍应在目标版本上实测。

## 构建

需要 Java 21+ 与 Maven：

```bash
mvn package
```

生成文件：`target/HumanVerify-<版本>.jar`（版本号见最新 Release）。将其放入服务端 `plugins/` 目录。该 JAR 使用 Paper API 的 `provided` 依赖，不需要额外安装 CubeXLib 或其他运行库。

## API 调用

### 完整接入流程

API 的完全限定类名：

```text
org.cubexmc.humanverify.api.HumanVerifyApi
org.cubexmc.humanverify.api.HumanVerifyEvent
org.cubexmc.humanverify.api.VerificationResult
```

普通 `requestVerification(player)` 是幂等调用：玩家已经在本次在线会话中通过验证时，会直接返回 `SUCCESS`，不会重复打开界面；玩家已有未完成的验证时，会复用该会话（重新打开同一界面，**不会**重置错误次数）。需要让玩家再次完成验证时，使用 `requestVerification(player, true)`。

联动插件可用 `api.isPendingVerification(player)` 查询玩家是否正处于“未完成验证（含失败后等待重开）”状态——这正是冻结生效的状态；`isVerified` 只表示已通过。

其他插件应在 `plugin.yml` 中声明依赖，确保 HumanVerify 先加载：

```yaml
depend:
  - HumanVerify
```

编译时将最新 Release 的 `HumanVerify-<版本>.jar` 作为只编译依赖引入。Maven 示例（版本号替换为实际使用的版本）：

```xml
<dependency>
    <groupId>org.cubexmc</groupId>
    <artifactId>human-verify</artifactId>
    <version>1.3.0</version> <!-- 替换为实际使用的版本，见 Releases 页 -->
    <scope>provided</scope>
</dependency>
```

运行时不要把 HumanVerify JAR 打进自己的插件；服务器的 `plugins/` 目录中只保留一份 HumanVerify。

获取服务并发起验证：

```java
import org.cubexmc.humanverify.api.HumanVerifyApi;
import org.cubexmc.humanverify.api.VerificationResult;
import org.bukkit.Bukkit;

HumanVerifyApi api = Bukkit.getServicesManager().load(HumanVerifyApi.class);
if (api == null) {
    getLogger().warning("HumanVerify service is unavailable.");
    return;
}

if (api.isVerified(player)) {
    continueGame(player);
    return;
}

api.requestVerification(player).thenAccept(result -> {
    if (result == VerificationResult.SUCCESS) {
        continueGame(player);
        return;
    }
    handleVerificationFailure(player, result);
});
```

强制重新打开验证界面：

```java
api.requestVerification(player, true).thenAccept(result -> {
    if (result == VerificationResult.SUCCESS) {
        continueGame(player);
    }
});
```

如果回调中需要执行 Bukkit/Paper/Folia 的实体操作，建议切回玩家实体调度器：

```java
api.requestVerification(player).thenAccept(result ->
    player.getScheduler().run(plugin, task -> {
        if (result == VerificationResult.SUCCESS && player.isOnline()) {
            continueGame(player);
        } else if (player.isOnline()) {
            handleVerificationFailure(player, result);
        }
    }, null)
);
```

`VerificationResult` 的含义：

```text
SUCCESS    玩家正确完成验证
FAILED     错误次数达到 max-attempts
EXPIRED    超过 timeout-seconds
CANCELLED  玩家退出、插件关闭或验证被新的请求替换
```

> **事件一致性说明**：除离线 null-player 的极端情况外，所有 Future 终态都会伴随一次同 result 的 `HumanVerifyEvent`（包括 bypass 快速 `SUCCESS` 和退出时的 `CANCELLED`）。

管理员或其他可信插件可以直接标记玩家为已验证：

```java
api.markVerified(player);
// 或：api.markVerified(player.getUniqueId());
```

撤销已验证状态：

```java
api.revokeVerification(player.getUniqueId());
```

撤销只会移除已验证标记，不会自动打开验证界面。如需撤销后立即验证：

```java
api.revokeVerification(player.getUniqueId());
api.requestVerification(player);
```

如果需要一次调用完成"撤销并重新验证"，可直接使用 `requestVerification(player, true)`。

监听所有验证结束事件：

```java
import org.cubexmc.humanverify.api.HumanVerifyEvent;
import org.cubexmc.humanverify.api.VerificationResult;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class VerifyListener implements Listener {
    @EventHandler
    public void onHumanVerify(HumanVerifyEvent event) {
        if (event.getResult() != VerificationResult.SUCCESS) {
            return;
        }
        continueGame(event.getPlayer());
    }
}
```

## 配置

首次启动会生成 `plugins/HumanVerify/config.yml`，可调整：

- `auto-verify-on-join`
- `timeout-seconds`
- `max-attempts`
- `challenge-size`
- `verification-mode`：`COLOR`、`MATERIAL`、`SEQUENCE`、`COUNT`、`ODD_ONE_OUT`、`CENTER`、`CORNER`、`MATH`、`REVERSE`、`LINE`、`MEMORY`、`REACTION` 或 `RANDOM`
- `enabled-modes`：`RANDOM` 模式可随机选择的模式列表
- `target-material`、`sequence-material`
- `sequence-length`、`target-count`
- `math-max-sum`（默认 20）、`math-option-count`（默认 4，范围 2–9）
- `memory-count`（默认 3，范围 1–9，难度升级时每级 +1）、`memory-show-ticks`（默认 60 = 3 秒）
- `reaction-base-ticks`（默认 40 = 2 秒）、`reaction-extra-ticks`（默认 60 = 3 秒随机加量）
- `count-material`、`odd-one-out-material`、`odd-one-out-target-material`
- 三种按钮材质与消息文本

### 验证期间冻结

验证期间（玩家尚未通过验证时），以下行为默认被冻结：

| 开关 | 默认值 | 控制范围 |
|------|--------|----------|
| `freeze-unverified` | `true` | 总开关，设为 `false` 关闭所有冻结 |
| `freeze-movement` | `true` | 禁止移动（方块坐标变化）、传送、开启飞行/滑翔、乘骑载具 |
| `freeze-interact` | `true` | 禁止交互、挖掘、放置、攻击、受伤、丢弃物品、换手、摆弄盔甲架、进食/饮用、拾取掉落物、上床睡觉、用水桶装水/倒水、钓鱼、剪羊毛、拴绳/解绳 |
| `freeze-chat` | `true` | 禁止聊天 |
| `freeze-commands` | `true` | 禁止使用指令 |
| `command-whitelist` | `[/login, /register]` | 白名单指令（按指令词精确匹配，如 `/login 密码` 放行，`/loginfoo` 拦截），`freeze-commands=true` 时放行 |

### 失败 / 超时动作

```yaml
fail-action: RETRY    # KICK 或 RETRY（默认 RETRY = 自动重开新验证）
expire-action: RETRY  # KICK 或 RETRY（默认 RETRY = 自动重开新验证）
retry-delay-ticks: 20 # RETRY 时延迟多少 tick 后重开验证（20 tick = 1 秒，最小为 1）
fail-kick-message: '&c验证失败次数过多，已被移出服务器。'
expire-kick-message: '&c验证超时，已被移出服务器。'
```

- `RETRY`：关闭当前验证界面，延迟一小段时间（`retry-delay-ticks`）后自动弹出新验证；等待期间玩家保持冻结。
- `KICK`：直接踢出玩家，显示对应的踢出消息。注意：被踢玩家的失败计数**不清零**，重进后难度保持（防止靠重进刷回简单题）。

### 难度进阶 / 反脚本

连续失败（`FAILED`/`EXPIRED`）会提升后续验证难度，成功通过后清零（退出重进不清零）：

```yaml
difficulty-escalation: true # 总开关
escalation-fail-step: 2     # 每失败 2 次升 1 级
escalation-max-level: 3     # 最高 3 级
escalation-timeout-penalty: 10 # 每级超时减少 10 秒（最低 15 秒）
escalation-math-bonus: 10   # 每级 MATH 和的上限 +10
```

每级效果：`SEQUENCE`/`REVERSE` 长度 +1、`COUNT`/`LINE` 目标 +1、MATH 更难、超时更短。

```yaml
anti-flood: true      # 滑动窗口内点击过多则消耗一次尝试
click-window-ms: 3000 # 窗口 3 秒
click-max-clicks: 12  # 超过 12 次点击判为刷点击
verify-cooldown-seconds: 10 # 自助 /hv verify 冷却（秒），0 关闭
max-pending-per-ip: 3       # 同 IP 并发待验证上限（0 不限），超限拒绝新验证
```

### 解题耗时判定 / 标题混淆 / 连过机制

脚本读包后可以瞬间解题，但人类需要视觉搜索 + 移动鼠标（通常 300ms 以上）。插件记录每轮“可点击时刻 → 解出”的耗时与点击间隔：

```yaml
solve-time-check: true # 总开关
solve-min-ms: 600      # 解题快于 600ms 判为脚本（0 关闭）
fast-click-ms: 120     # 任意两次点击间隔 < 120ms 即判脚本
solve-bot-action: KICK # 判脚本后 KICK（或 RETRY 重开并升级难度）
```

判脚本后计入一次失败（升级难度），默认直接踢出（`bot-kick-message` 可自定义）。

其他两项：

```yaml
title-shuffle: true # 每轮 GUI 标题追加随机后缀，防按标题识别的低端脚本
combo-enabled: true # 新 IP 需连过 combo-rounds 轮才放行
combo-rounds: 2     # 默认 2 轮；已知 IP（已验证过至少一人）、1 = 单轮
```

连过中间轮不会提前放行：API 的 Future 只在最后一轮终结；失败/超时会打断连过重新计数。管理员强制验证（`force`）跳过连过要求。

### 验证方式

- `COLOR`：在普通按钮中找出唯一的 `correct-material`，默认是绿色羊毛。
- `MATERIAL`：在普通按钮中找出唯一的 `target-material`，默认是钻石。
- `SEQUENCE`：按 `1`、`2`、`3` 等编号顺序点击 `sequence-length` 个方块；顺序错误会消耗一次尝试次数。
- `COUNT`：点击 `target-count` 个目标方块，目标可以按任意顺序点击，重复点击会视为错误。
- `ODD_ONE_OUT`：在一组相同材质的方块中找出唯一不同的方块。
- `CENTER`：点击验证界面的中心位置方块。
- `CORNER`：点击验证界面四个角落之一的目标方块。
- `MATH`：计算 `a + b`（`math-max-sum` 控制和的上限，`math-option-count` 控制选项数），点击显示正确答案的按钮。
- `REVERSE`：与 `SEQUENCE` 相反，按编号从大到小的顺序点击目标方块。
- `LINE`：点击随机某一行的全部 9 个目标方块（任意顺序）。
- `MEMORY`：界面先点亮 `memory-count` 个方块并展示 `memory-show-ticks`（默认 3 秒），熄灭后凭记忆点回（任意顺序，展示期间点击不计）。
- `REACTION`：按钮先红后绿，变绿前点击算失败（扣一次尝试），变绿后点击通过。等待时长 `reaction-base-ticks` + 随机 `0 ~ reaction-extra-ticks`。
- `RANDOM`：每次验证从 `enabled-modes` 中随机选择一种方式；列表为空或配置无效时回退到 `COLOR`。

例如，固定使用顺序验证：

```yaml
verification-mode: SEQUENCE
sequence-length: 4
```

例如，只在颜色和材质验证中随机选择：

```yaml
verification-mode: RANDOM
enabled-modes:
  - COLOR
  - MATERIAL
```

### 配置版本迁移

插件使用 `config-version` 追踪配置版本。升级插件后，新配置项会自动以默认值补充到已有 `config.yml` 中，无需手动合并。用户已自定义的值不会被覆盖。

按钮材质不能与正确、错误或目标材质相同；如果配置冲突，插件会在启动或重载时记录警告并使用安全回退材质。

`challenge-size` 会被限制在 9 至 54 格，并自动调整为 9 的倍数。验证状态默认只在本次在线会话内保存；玩家退出后下次进入需要重新验证。管理员或其他插件可以通过 `markVerified` 临时放行，通过 `revokeVerification` 撤销放行。

## 已知限制

- 验证 GUI 期间死亡/传送导致的界面关闭会自动重开，这是正常行为。
- `correct-material`、`wrong-material` 和 `button-material` 只校验是否为可用物品材质；如果修改为与提示文字不匹配的材质，需要同时修改 `messages.instructions`。

## 常见问题

- **玩家说验证太难？** 检查 `difficulty-escalation` 与 `escalation-max-level`：连续失败会升级，后台可用 `/humanverify verify <玩家>` 为其强制重开（`force` 会连同当前题目一起重置，但失败计数不清零，难度保持）。
- **被机器人刷验证？** 确认 `anti-flood: true`（刷点击扣次数）、`verify-cooldown-seconds`（自助命令限流）、`max-pending-per-ip`（同 IP 并发上限，建议内网/登录服场景按需调大）。
- **想关掉某类冻结？** `freeze-movement` / `freeze-interact` / `freeze-chat` / `freeze-commands` 可独立关闭；`freeze-unverified: false` 则全部关闭。
- **改了配置没生效？** 用 `/humanverify reload` 重载；升级后新增配置项会自动补写（`config-version` 当前为 6），已自定义的值不会被覆盖。
- **验证状态能跨服/跨重启保留吗？** 不能。验证状态只保存在内存本次会话内，玩家退出即清除（失败计数与自助冷却除外）；重启/重载后未验证玩家会重新验证。
