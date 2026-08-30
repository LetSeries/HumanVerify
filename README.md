# HumanVerify

一个面向高版本 Minecraft 服务端的游戏内人机验证插件，支持 **Paper、Folia、Purpur**。

## 项目状态

- 当前版本：`1.0.0`
- 当前构建状态：`mvn clean verify` 已通过。
- 当前没有自动化测试源码，构建日志显示 `No tests to run`；建议在目标 Paper/Folia/Purpur 服务端进行实际 GUI 与事件测试。
- 项目**没有使用** [CubeX-MC/Plugins](https://github.com/CubeX-MC/Plugins) 框架。
- 本项目是独立 Maven 项目，直接依赖 Paper API；没有 CubeX 的 Gradle Kotlin DSL、`buildSrc`、`cubex-*` 模块、`CubeXLib` 或 Shadow 打包配置。

## 功能

- 玩家进入服务器后自动打开验证界面。
- 27/36/45/54 格背包中随机放置唯一绿色方块，玩家点击正确方块即可通过。
- 支持验证超时与错误次数限制。
- 支持多种验证方式：唯一颜色方块、唯一材质方块、按编号顺序点击方块、点击指定数量目标方块、找出唯一不同方块、点击中心或角落方块；可固定模式或随机选择。
- 通过 Bukkit `ServicesManager` 暴露公共 API，其他插件无需依赖实现包即可调用。
- 使用 Paper/Folia `EntityScheduler`，不依赖传统全局调度器，兼容 Folia 区域线程模型。
- 提供 `/humanverify verify`、`/humanverify verify <玩家>`、`/humanverify reload`。

管理员执行 `/humanverify verify <玩家>` 会为目标玩家打开一次真实验证，不会直接将其标记为已验证；如需直接放行其他插件，可调用 API 的 `markVerified`。

## 运行环境

- Java 21 或更高版本。
- Paper API `1.21.8-R0.1-SNAPSHOT`，因此实际服务端应使用兼容的 Minecraft 1.21.x 版本。
- `plugin.yml` 已声明 `folia-supported: true`；Purpur 通常兼容 Paper API，但仍应在目标版本上实测。

## 构建

需要 Java 21+ 与 Maven：

```bash
mvn package
```

生成文件：`target/HumanVerify-1.0.0.jar`。将其放入服务端 `plugins/` 目录。该 JAR 使用 Paper API 的 `provided` 依赖，不需要额外安装 CubeXLib 或其他运行库。

## API 调用

### 完整接入流程

API 的完全限定类名：

```text
org.cubexmc.humanverify.api.HumanVerifyApi
org.cubexmc.humanverify.api.HumanVerifyEvent
org.cubexmc.humanverify.api.VerificationResult
```

普通的 `requestVerification(player)` 是幂等调用：玩家已经在本次在线会话中通过验证时，会直接返回 `SUCCESS`，不会重复打开界面。需要让玩家再次完成验证时，使用 `requestVerification(player, true)`。

其他插件应在 `plugin.yml` 中声明依赖，确保 HumanVerify 先加载：

```yaml
depend:
  - HumanVerify
```

编译时将 `HumanVerify-1.0.0.jar` 作为只编译依赖引入。Maven 示例：

```xml
<dependency>
    <groupId>org.cubexmc</groupId>
    <artifactId>human-verify</artifactId>
    <version>1.0.0</version>
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
        // 只有 SUCCESS 才能执行受保护逻辑。
        continueGame(player);
        return;
    }

    // FAILED、EXPIRED、CANCELLED 都不能继续执行成功逻辑。
    handleVerificationFailure(player, result);
});
```

强制重新打开验证界面：

```java
api.requestVerification(player, true).thenAccept(result -> {
    if (result == VerificationResult.SUCCESS) {
        // 玩家完成了这次新的验证。
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

不要无条件执行成功逻辑：

```java
// 错误示例：失败、超时和取消时也会继续执行。
api.requestVerification(player).thenAccept(result -> continueGame(player));
```

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

如果需要一次调用完成“撤销并重新验证”，可直接使用 `requestVerification(player, true)`。

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
- `verification-mode`：`COLOR`、`MATERIAL`、`SEQUENCE`、`COUNT`、`ODD_ONE_OUT`、`CENTER`、`CORNER` 或 `RANDOM`
- `enabled-modes`：`RANDOM` 模式可随机选择的模式列表
- `target-material`、`sequence-material`
- `sequence-length`、`target-count`
- `count-material`、`odd-one-out-material`、`odd-one-out-target-material`
- 三种按钮材质与消息文本

按钮材质不能与正确、错误或目标材质相同；如果配置冲突，插件会在启动或重载时记录警告并使用安全回退材质。

`challenge-size` 会被限制在 9 至 54 格，并自动调整为 9 的倍数。验证状态默认只在本次在线会话内保存；玩家退出后下次进入需要重新验证。管理员或其他插件可以通过 `markVerified` 临时放行，通过 `revokeVerification` 撤销放行。

### 验证方式

- `COLOR`：在普通按钮中找出唯一的 `correct-material`，默认是绿色羊毛。
- `MATERIAL`：在普通按钮中找出唯一的 `target-material`，默认是钻石。
- `SEQUENCE`：按 `1`、`2`、`3` 等编号顺序点击 `sequence-length` 个方块；顺序错误会消耗一次尝试次数。
- `COUNT`：点击 `target-count` 个目标方块，目标可以按任意顺序点击，重复点击会视为错误。
- `ODD_ONE_OUT`：在一组相同材质的方块中找出唯一不同的方块。
- `CENTER`：点击验证界面的中心位置方块。
- `CORNER`：点击验证界面四个角落之一的目标方块。
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

## 已知限制

- 项目目前没有单元测试或集成测试，验证逻辑需要通过实际服务端验证。
- `correct-material`、`wrong-material` 和 `button-material` 只校验是否为可用物品材质；如果修改为与提示文字不匹配的材质，需要同时修改 `messages.instructions`。
- 旧配置文件不会自动覆盖新增配置项；升级后如需启用新模式，请手动将新字段合并到 `plugins/HumanVerify/config.yml`。
