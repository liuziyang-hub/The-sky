# Realtime Debug SDK

> Android 局域网 / USB 实时调试 SDK：抓包 · 断言 · Mock · 弱网 · 日志 · 性能 · 画面预览  
> 仓库：[liuziyang-hub/The-sky](https://github.com/liuziyang-hub/The-sky)

---

## 这是什么

把调试能力封装成独立 Android Library + PC 控制台。宿主 App **两行代码**接入；测试同学在浏览器里看请求、做断言、下发 Mock/弱网。

| 交付物 | 路径 | 说明 |
|--------|------|------|
| Android SDK | `library/` | 入口 `com.maiya.realtimedebug.RealtimeDebug` |
| PC 控制台 | `console/` | `start-console.bat` → http://127.0.0.1:8765/ |
| 图文方案 | `docs/tech-spec.html` | 架构图 + 功能说明（浏览器打开） |
| 交付说明 | [DELIVERY.md](./DELIVERY.md) | 给研发 / 测试的交接清单 |

---

## 快速接入（研发）

### 1. 引入模块

**方式 A · 源码依赖（推荐）**

```gradle
// settings.gradle
include ':library'
project(':library').projectDir = new File(settingsDir, '../The-sky/library')
// 或把本仓库作为 submodule / 直接拷贝 library 目录

// app build.gradle —— 仅 debug / 测试包
debugImplementation project(':library')
```

**方式 B · 本工程一起编译**

```bat
git clone https://github.com/liuziyang-hub/The-sky.git
```

用 Android Studio 打开本仓库根目录，同步 Gradle 后即可产出 AAR。

### 2. 代码

```java
// Application.onCreate —— 仅 Dev/Debug
RealtimeDebug.install(this);
RealtimeDebug.setUidProvider(() -> yourUserId()); // 可选，多机靠 UID

// OkHttpClient.Builder
RealtimeDebug.installOkHttp(builder); // 抓包 + Mock/弱网
```

### 3. 启动控制台

```bat
cd console
start-console.bat
```

浏览器打开 http://127.0.0.1:8765/

- **局域网**：自动扫描 → 点选 **机型卡片** 连接（无需手输 UID）
- **USB**：跨网时切 USB →「建立 USB 通道并连接」

---

## 功能一览

| 能力 | 说明 |
|------|------|
| 抓包 | 过滤、Replay、cURL、导出 JSON/HAR |
| 断言 | 状态码 / JSONPath / 耗时（控制台本地） |
| Mock | 按 URL 匹配返回；支持分组、导入/导出；请求详情可一键生成 |
| Rewrite | 改 URL/方法/请求头/Body 后仍走真实网络 |
| 弱网 | 3G / 慢网 / 丢包 / 自定义延迟与失败率 |
| 观测 | 实时日志、CPU/内存/线程、约 1fps 画面 |
| 发现 | 同网 UID+机型列表点选；USB adb forward |

---

## 对外 API

| API | 说明 |
|-----|------|
| `RealtimeDebug.install(Context)` | 启动 WS / 日志 / 指标 / 画面 / 广播 |
| `setUidProvider(...)` | 注入登录用户 ID |
| `installOkHttp(builder)` | **推荐** 挂载抓包 + Mock |
| `getUid()` / `getWsUrl()` / `stop()` | 查询与停止 |

---

## 端口

| 端口 | 用途 |
|------|------|
| 17890 | 手机 WebSocket Server |
| 17891 | UDP 局域网发现 |
| 8765 | PC 控制台 HTTP |

---

## 注意

- **仅进 Dev/Debug 包**，不要打进正式 Release
- 手机须安装带本 SDK 的调试包并打开 App
- 同网列表为空时优先用 USB，或点「刷新扫描」

---

## 文档

- [交付文档 DELIVERY.md](./DELIVERY.md)
- [技术方案（图文）docs/tech-spec.html](./docs/tech-spec.html)
- [控制台说明 console/README.md](./console/README.md)

## License

Internal / 按团队约定使用。
