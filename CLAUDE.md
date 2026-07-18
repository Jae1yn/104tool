# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目定位

IEC 60870-5-104 主站替身桌面工具（JavaFX），用于联调监控系统的子站侧实现。语言约定：代码注释、UI 文案、提交信息均为中文。设计文档与 ADR 在 Obsidian vault `~/Documents/Obsidian/Lilith OS/01_Projects/104工具/`（项目状态笔记 `104工具.md` 随功能交付同步更新）。

## 常用命令

```bash
./mvnw test                                # 全量测试（含真实 TCP 回环的集成测试，约 1 分钟）
./mvnw test -Dtest=J60870MasterSessionTest # 单个测试类
./mvnw javafx:run                          # 本机运行 UI
./mvnw -DskipTests clean package           # fat jar（本机平台，target/tool104-*.jar 可直接 java -jar）
./package-win.sh                           # Windows 绿色便携包 → dist/104tool-win-x64.zip
```

- Windows 打包在 Mac 上交叉完成：`-Djavafx.platform=win` 拉取 JavaFX Windows 原生库打进 fat jar，捆绑 Temurin JRE（**JavaFX 24 类文件版本 66，必须 JRE 22+**，当前捆绑 25；JRE 缓存在 `~/.cache/tool104-build/`）。
- fat jar 入口是 `tool104.app.Launcher`（不继承 Application，绕过 JavaFX 主类检查），不要把 shade 主类改回 `Main`。
- 手动 e2e：`tool104.e2e.SimulatedSubstation <host> <port>`（子站作客户端拨入，配工具监听模式）或 `SimulatedSubstationServer <port>`（子站作服务端，配工具拨号模式），在 test classpath 下运行。

## 架构

装配在 `app/Main`（无业务逻辑）：加载配置 → 构造各 store → 接线 UI 与会话 → 事件经 `Platform.runLater` 封送到 FX 线程。

**协议层（核心约束：`protocol/j60870/J60870MasterSession` 是全工程唯一 import openmuc j60870 的地方）**：

- `protocol/MasterSession` 接口 + `protocol/model/*` 与协议库解耦；测试用 `FakeMasterSession` 桩。
- **连接代次机制（防连接泄漏，勿破坏）**：`connectGeneration` 在每次 start/stop/reconfigure 递增；每条连接的监听器绑定创建时代次，过期代次的一切事件（收帧、断开重拨、状态变化）一律忽略并就地关闭；`dialOnce` 在 TCP 建立后与 STARTDT 完成后各复核一次代次。历史教训：缺这套机制时重复拨号循环产生幽灵连接，现场表现为"对端反复踢连接"。回归测试 `DialReconfigureLeakTest`。
- **原始帧捕获**：j60870 无原始报文回调，经 builder 的 `setSocketFactory` 换入 `TappedSocket`（委托式包装，按 `0x68+长度` 定界切 APDU）。帧与摘要的配对：发出方向用 ThreadLocal 捕获槽包裹 send 调用；接收 I 帧用**会话级 FIFO 队列**（`newASdu` 回调不在读线程上，ThreadLocal 不可行——实测踩过）；U/S 帧直接生成独立日志条目。配对失败优雅降级为无 hex 摘要。
- 遥控/遥调共用 `sendConfirmedCommand`：按 IOA 挂起 pendingCommands，等 ACTCON 兑现或超时；确认帧回显值同时 decode 进实时数据。
- `protocol/ApduExplainer`：独立于 j60870 的逐字段帧剖析（详情区用），未知类型降级为头部+裸字节，任何输入不抛异常。

**Store 层（`config`/`pointtable`/`livedata`/`framelog`）**：线程安全，统一用 CopyOnWriteArrayList 订阅模式通知（`subscribe`/`subscribeClear`/`subscribeRemove`），回调发生在调用线程，UI 侧自行封送 FX 线程。`LiveDataStore.syncControlPoints` 让点表可控点常驻实时表（占位「未下发」）；触发点：启动、点表变更、清空后（Main 里接线）。

**行为约定**（改动时保持）：打开工具不自动连接；设置确认后未运行则自动 start、运行中按需重启并清空数据/报文；默认拨号模式，但旧 settings.json 缺 `connectionMode` 回落 LISTEN（升级不改既有行为）。

## 测试结构

- 单测与集成测试混在 `src/test`：`*Test` 全部进 CI 路径；`spike/` 是留档的探索性测试（也会跑）。
- 集成测试模式：真实 TCP 回环 + j60870 对端角色 + BlockingQueue 收事件断言（参照 `J60870MasterSessionTest`）。
- e2e 模拟子站脚本（`e2e/`）非 JUnit，供手动联调。

## 本机环境注意

- 本机代理不放行 SSH（22/443 均被拒），GitHub 只能走 HTTPS + 钥匙串令牌；全局 gitconfig 有 HTTPS→SSH 的 insteadOf 改写，本仓库用 repo-local 自改写规则绕开——**勿删本仓库 `git config --local` 里的 `url.*.insteadOf`**。
- 交付产物：`dist/104tool-win-x64.zip` 拷现场 Windows 10/11 x64 机器，解压双击 `104tool.bat`。
