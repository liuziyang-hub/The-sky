# Realtime Debug Console

PC 端发现 + 可视化控制台（随 `library-realtime-debug` 分发）。

```bat
start-console.bat
```

http://127.0.0.1:8765/

## 模式
- **局域网**：输入手机 UID 连接
- **USB**：adb forward 跨网调试

## 工具页（右侧）
- **请求**：Replay / cURL / 导出 JSON·HAR
- **断言**：状态码、JSONPath、耗时（本地）
- **Mock**：改码/改 Body/Abort，同步到手机
- **弱网**：3G / 慢网 / 丢包 / 自定义延迟

需要 Python 3；USB 模式需要 `adb`。
