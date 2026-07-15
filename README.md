# 104工具（主站替身）

IEC 60870-5-104 主站替身桌面工具：开发/联调阶段模拟主站，作为 TCP 服务端监听，等待监控系统子站侧（TCP 客户端）连入。支持总召唤、时钟同步、接收遥测/遥信、按点表下发单点遥控（C_SC_NA_1 直接执行）。

- 协议层：openmuc j60870（GPLv3，内部工具使用）
- UI：JavaFX
- 设计文档与 ADR：Obsidian vault `Lilith OS/01_Projects/104工具/docs/`

## 运行

```bash
mvn javafx:run
```

## 测试

```bash
mvn test
```

## 打包 Windows 绿色便携版

```bash
./package-win.sh
```

产物为 `dist/104tool-win-x64.zip`（Windows 10/11 x64，自带 Temurin JRE 25（JavaFX 24 要求 JRE 22+），解压后双击 `104tool.bat` 即用，目标机器无需安装 Java）。首次运行会下载 JRE 并缓存到 `~/.cache/tool104-build/`。

- fat jar 构建：`mvn -Djavafx.platform=win package`（shade 插件打入 JavaFX Windows 原生库）
- fat jar 入口为 `tool104.app.Launcher`（不继承 Application，绕过 JavaFX 主类检查）
