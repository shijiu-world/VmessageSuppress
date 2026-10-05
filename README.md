# VmessageSuppress

[Vmessage](https://github.com/shijiu-world/Vmessage)（跨服聊天）的**子服配套插件**。
把「这条聊天被子服插件取消掉了」告诉代理，避免输入内容跨服泄露。

单类、零依赖，**每个要用跨服聊天的子服都要装**。

---

## 它解决什么问题

商店让玩家输入购买数量、菜单让输入颜色值、签到让输入关键词 —— 这些插件都会
`setCancelled(true)` 把这条聊天吞掉。但代理收到聊天的**同一时刻**就把消息发给了别的子服，
而子服的取消发生在**之后**，且没有任何机制能回传。

结果：别的子服会看到玩家输入的「64」「abc」，玩家自己却以为那句话没发出去。

装了本插件之后：子服一旦发现这条聊天被取消，立刻通过插件消息通知代理，
代理在转发前把这条拦下来 —— **跨服转发、`commands`、Discord 转发一起都不做**。

## 安装

1. `VmessageSuppress-1.1.1.jar` 丢进**每个子服**的 `plugins/`
2. 重启子服（子服端没有 reload 命令）
3. 代理端 Vmessage 的 `config.toml` 里确认 `Message.await-cancel-signal = true`

日志里看到下面这句就说明生效了：

```
[VmessageSuppress] 已启用：被取消的聊天会通知代理（通道 vmessage:suppress）
```

> **不装会怎样？** 不会报错，也不会漏发消息 —— 代理每次等到 `await-cancel-timeout-millis`
> （默认 100ms）超时后照常转发。失效模式是安全的，代价是别的子服看到消息晚一点，
> 且被取消的输入照样会泄露。

## 配置

`plugins/VmessageSuppress/config.yml`：

```yaml
# 是否在后台日志里打印每一次发出的抑制信号
# 排查时开 true（能看到「哪条聊天被子服取消了」），平时建议 false —— 商店输入一天能刷几百行
debug: false
```

改完重启子服。

## 原理

`AsyncPlayerChatEvent` 上挂两个监听器：

| 优先级 | 干什么 |
| --- | --- |
| **LOWEST** | 记下玩家真正输入的**原文** |
| **MONITOR** | 此刻 `isCancelled()` 已是最终结果；为真就走 `vmessage:suppress` 通道回传 `(玩家UUID, 原文)` |

两个细节：

- **记原文而不是事件里的 `message`**：万一中间有别插件改写过，代理端拿原始输入比对才对得上号。
- **直接发、不排主线程**：代理那边只有一个几十毫秒的窗口在等，多绕一个 tick 就可能错过。
- 只有被取消时才发包，正常聊天零额外开销。

## 从源码构建

```bash
./build.sh
# 或指定服务端 jar：
SERVER_JAR=D:/game/Server/killer/paper-1.21.4-138.jar ./build.sh
```

产物：`target/VmessageSuppress-1.1.1.jar`（`--release 17`，兼容 1.17+ 服务端）。

手工编译等价命令：

```bash
javac -encoding UTF-8 --release 17 -cp <服务端 API jar> -d out \
    src/main/java/cn/shijiu/vmessagesuppress/VmessageSuppress.java
cp src/main/resources/plugin.yml src/main/resources/config.yml out/
jar cf target/VmessageSuppress-1.1.1.jar -C out .
```

> 为什么不用 Maven：只有一个类、只依赖 Bukkit API，引入 Maven 反而要联网拉插件。

## 相关

| 项目 | 关系 |
| --- | --- |
| [Vmessage](https://github.com/shijiu-world/Vmessage) | 主插件，装在**代理**上。本插件是它的子服配套 |
| [VWhisper](https://github.com/shijiu-world/VWhisper) | 跨服私聊，与本插件无关 |
| [VTpa](https://github.com/shijiu-world/VTpa) | 跨服传送请求，与本插件无关 |
