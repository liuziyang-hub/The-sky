# Realtime Debug · 交付文档

面向：**研发接入 / 测试使用 / 项目管理验收**

仓库：https://github.com/liuziyang-hub/The-sky

---

## 1. 交付清单

| 序号 | 内容 | 路径 | 验收标准 |
|------|------|------|----------|
| 1 | Android Library 源码 | `library/` | 可被宿主 `debugImplementation` 引用编译 |
| 2 | PC 控制台 | `console/` | `start-console.bat` 后打开 8765 页面 |
| 3 | 图文技术方案 | `docs/tech-spec.html` | 浏览器可打开，含架构图 |
| 4 | 接入说明 | `README.md` | 两行代码可完成接入说明 |
| 5 | 本交付清单 | `DELIVERY.md` | 本文件 |

**不包含**：正式包混淆配置以外的业务 App 代码；Chucker / DoKit（宿主自选）。

---

## 2. 背景与目标

测试需要在局域网或 USB 场景下实时看手机网络请求、日志、性能，并支持断言、Mock、弱网。  
本仓库将能力封装为 **第三方可调用 SDK**，避免拷贝散落代码。

---

## 3. 架构摘要

```
手机 Dev App                     PC
┌─────────────────┐            ┌──────────────────┐
│ RealtimeDebug   │  WS:17890  │ console UI :8765 │
│ OkHttp 拦截器   │◄──────────►│ 发现 / 工具面板  │
│ UDP Beacon:17891│            │ Mock/弱网下发    │
└─────────────────┘            └──────────────────┘
```

- 手机：WebSocket Server，推送 `http/log/metrics/screen`
- PC：发现设备（UDP / 端口扫描 / adb forward），下发 `cmd`（Mock、弱网、Replay）

详细图文：打开 `docs/tech-spec.html`。

---

## 4. 研发接入步骤（验收用）

1. Clone 本仓库或拷贝 `library` 目录到宿主工程  
2. `settings.gradle` include 模块；`debugImplementation project(':library')`  
3. `RealtimeDebug.install(app)` + `RealtimeDebug.installOkHttp(builder)`  
4. 打 **Dev/Debug** 包安装到真机  
5. PC 运行 `console/start-console.bat`  
6. 局域网点选机型连接，或 USB 一键连接  
7. 在控制台看到网络请求 / 可同步一条 Mock 规则 → 手机请求被 Mock

---

## 5. 测试使用步骤

1. 确认手机为 **带 SDK 的 Dev 包**，打开 App 过开屏  
2. 启动控制台 → http://127.0.0.1:8765/  
3. **同网**：点「刷新扫描」→ 卡片显示机型+UID → 点选连接  
4. **跨网**：USB 调试 → 切 USB 模式 →「建立 USB 通道并连接」  
5. 右侧：断言 / Mock / 弱网；改完 Mock/弱网点「同步到手机」  
6. 可选：`python console/sys_test.py` 跑自动化连通性（需 USB）

---

## 6. 功能验收表

| 功能 | 操作 | 期望 |
|------|------|------|
| 设备发现 | 同网刷新扫描 | 出现机型卡片，含 UID |
| 点选连接 | 点卡片 | 状态「已连接」 |
| 抓包 | App 内发请求 | 列表出现 http 事件 |
| Replay | 详情点 Replay | 再次产生请求事件 |
| Mock | 配置 URL 规则并同步 | 匹配请求返回 Mock 状态码 |
| 弱网 | 应用 3G/慢网 | 请求耗时明显增加 |
| 断言 | 配置期望状态码 | 失败时状态栏/结果区提示 |
| USB | adb 设备在线 | USB 模式可连 |

---

## 7. 端口与依赖

| 端口 | 用途 |
|------|------|
| 17890 | 手机 WS |
| 17891 | UDP 发现 |
| 8765 | 控制台 |

**依赖**：OkHttp 4.4.1、Java-WebSocket 1.5.7、AndroidX Annotation  
**运行控制台**：Python 3；USB 需本机 `adb`

---

## 8. 已知限制

- 仅 Debug/Dev；Release 勿引入  
- 部分企业网 /23 网段需依赖扫描或 USB；防火墙可能拦 UDP  
- 断言在 PC 本地执行；Mock/弱网在手机 OkHttp 生效  

---

## 9. 联系与版本

| 项 | 内容 |
|----|------|
| GitHub | https://github.com/liuziyang-hub/The-sky |
| 包名 | `com.maiya.realtimedebug` |
| 入口类 | `RealtimeDebug` |
| 建议 Tag | `v1.0.0` |

---

## 10. 给研发的一句话

> 接 `The-sky` 仓库的 `library`：`RealtimeDebug.install` + `installOkHttp`；PC 跑 `console/start-console.bat`。图文见 `docs/tech-spec.html`，交接见本 `DELIVERY.md`。
