# 104工具（主站替身）

IEC 60870-5-104 主站替身桌面工具：开发/联调阶段模拟主站，支持总召唤、时钟同步、接收遥测/遥信、按点表下发单点遥控（C_SC_NA_1 直接执行）。

两种连接方式（设置里切换）：

- **监听**（默认）：作为 TCP 服务端监听，等待子站侧（TCP 客户端）连入；本阶段单子站连接
- **拨号**：作为 TCP 客户端主动连接子站（标准端口 2404），断开后每 3 秒自动重连

技术栈：

- 协议层：openmuc j60870（GPLv3，内部工具使用）
- UI：JavaFX
- 设计文档与 ADR：Obsidian vault `Lilith OS/01_Projects/104工具/docs/`

## 使用要点

- 打开工具后**不会自动连接**，点状态栏的「连接子站/启动监听」启动
- 配置保存在 `~/.tool104/`（`settings.json`、`points.json`），Windows 为 `%USERPROFILE%\.tool104\`
- 设置里改了连接参数（方式/地址/端口/公共地址/超时）并确认后，会**自动重启连接并清空**实时数据与报文日志；只改「自动总召/对时」勾选不重启，下次连接生效
- 遥信（M_SP_NA_1 等）只在**变位**或**总召唤响应**时上送——连上后没有遥信值属正常，点「总召唤」或开启「连接后自动总召」即可
- 报文日志会记录断开原因（如 `EOFException - Connection was closed by remote` 表示对端主动断开，常见于端口被其他主站占用或 IP 白名单限制）

## 运行

```bash
mvn javafx:run
```

## 测试

```bash
mvn test
```

含单元测试、e2e 模拟子站（`SimulatedSubstation` 客户端角色 / `SimulatedSubstationServer` 服务端角色）与 spike 测试。

## 打包 Windows 绿色便携版

```bash
./package-win.sh
```

产物为 `dist/104tool-win-x64.zip`（Windows 10/11 x64，自带 Temurin JRE 25（JavaFX 24 要求 JRE 22+），解压后双击 `104tool.bat` 即用，目标机器无需安装 Java；启动异常时用 `104tool-debug.bat` 看控制台输出）。首次运行会下载 JRE 并缓存到 `~/.cache/tool104-build/`。

- fat jar 构建：`mvn -Djavafx.platform=win package`（shade 插件打入 JavaFX Windows 原生库）
- fat jar 入口为 `tool104.app.Launcher`（不继承 Application，绕过 JavaFX 主类检查）
- Windows 首次启动会弹防火墙提示（监听模式作为 TCP 服务端），需要允许
