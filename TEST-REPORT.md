# 验证记录

日期：2026-11-02。插件：git commit for ai 1.0.0。

## 已完成

- 使用本机 IntelliJ IDEA 2024.3.6（IU-243.26574.91）的平台与 Git SDK，JBR/JDK 21 编译全部生产源码。
- **19 项自动测试通过**，HTTP 请求只发送到本机临时模拟服务，没有调用真实付费模型。
- **两个独立 JVM 的 XML 持久化检查通过**：第一个进程保存多个模型、默认项、自定义模板和 Unicode；第二个进程从磁盘重新读取并校验。
- 渲染并逐页查看实际 `SettingsPanel` 的模型、Prompt、生成选项截图，修正字段对齐、表单密度及辅助文字对比度。截图使用 Nimbus 外观，真实 IDEA 继承其主题。
- 清理生产 class 输出后重新构建，检查安装 ZIP、插件描述符、入口 class、图标、MIT 许可证与字节码版本。

19 项测试覆盖：

1. Prompt 最终字符预算及占位符只替换一次。
2. 截断不切断 UTF-16 代理对。
3. 根目录与子目录中的 glob 排除规则。
4. SSE 分块、多行 data 与文件结束时尾块处理。
5. 在每个边界分块的 `<think>` 标签过滤。
6. IntelliJ 原生 XML 序列化/解析往返，重命名后模型 UUID 稳定，emoji 模板完整。
7. 旧配置、空枚举及重复 ID 规范化。
8. Key 留空保留、输入覆盖、显式清除的编辑语义。
9. OpenAI 兼容流式 UTF-8、无 `[DONE]` 尾帧。
10. 请求模型名、messages、鉴权头和地址。
11. 非流式 JSON 回复。
12. 流式请求收到完整 JSON 的兼容处理。
13. Ollama NDJSON。
14. HTTP 错误中的 Key 脱敏。
15. SSE 服务错误中的 Key 脱敏。
16. JSON 附加参数与禁止覆盖的核心字段。
17. 响应体空闲期间的取消，3 秒内结束。
18. 地址补全与查询参数保留。
19. `api-key` 请求头。

完整通过日志见 `test-results.txt`。

## IDE 启动检查的范围

另行创建了位于 `build/ide-smoke` 的隔离启动环境。IDE 的插件发现日志列出了 `git commit for ai (1.0.0)` 和测试辅助插件，确认描述符及包布局被识别。

完整启动检查没有完成：本机 Ultimate 的空白测试配置缺少可用授权，进程在执行测试 starter 前报告 `No valid license found`；Windows 沙箱还对 Java `toRealPath` 和注册表访问报错，本机 SDK 的部分非目标插件描述符也指向缺失文件。因此**不把“被发现”写成“Commit 工具窗口端到端已通过”**。没有修改用户日常 IDEA 的插件或配置，也没有绕过授权检查。

`smoke-local.ps1` 和 `src/smoke` 提供复验入口。该辅助插件与包含 `System.exit` 的测试 starter 不会打包进正式安装 ZIP。建议在完整的 Community SDK，或合法授权的独立 Ultimate 环境中运行。

## 待实际环境验证

- 用户真实模型服务的账号、网络、配额及特定参数兼容性。
- 真实 Commit 工具窗口中点击生成、部分行选择、取消及用户同时编辑的交互。
- 用户操作系统 PasswordSafe 提供者在 IDEA 真实重启后的 Key 恢复。测试从未读取或保存用户真实 Key；本次跨进程验证针对不含 Key 的配置数据。
- Gradle 在线下载构建路径和其他 IDEA 版本的兼容性。

这些范围不影响安装 ZIP 已成功构建，但仍需在目标 IDEA 中进行最终使用验证。
