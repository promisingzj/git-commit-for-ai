# git commit for ai

根据 Commit 窗口中勾选的 Git 变更生成提交信息。支持多个自定义模型、长期保存配置、可编辑的 Prompt 模板、实时预览与流式输出。

版本：1.0.0。开发及编译目标：IntelliJ IDEA 2024.3.6 / build 243，Java 21。插件声明最低 build 243；其他版本尚未逐一验证。

## 安装

在 [GitHub Releases](https://github.com/promisingzj/git-commit-for-ai/releases) 下载 `git-commit-for-ai-1.0.0.zip`，可使用同页的 SHA256SUMS 文件校验下载内容。

1. 在 IDEA 打开 **Settings → Plugins**。
2. 点击齿轮，选择 **Install Plugin from Disk…**。
3. 选择 `git-commit-for-ai-1.0.0.zip`，直接选择 ZIP，不要解压后选择源码。
4. 按 IDE 提示重启。
5. 打开 **Settings → Tools → git commit for ai**，也可在 Settings 搜索插件名称。

Git 插件须处于启用状态。无需安装原 AI Git Commit 插件。

## 配置模型

在“模型配置”页点击“＋ 添加”，填写名称、接口地址、模型 ID 与 API Key，然后点击 **Apply / 应用**。

- **OpenAI 兼容**：支持 Chat Completions 协议，可填服务 Base URL 或完整 `/chat/completions` 地址。支持 Bearer Token、`api-key` 请求头、无鉴权。
- **Ollama 原生**：切换协议后填写 `http://localhost:11434/api/generate` 或服务地址，模型 ID 填本机已安装的名称，通常选“无鉴权”。
- **附加参数**：可填 JSON，例如 `{"temperature":0.2,"max_completion_tokens":256}`。按服务支持情况填写；插件不会强制给所有模型附加温度参数。
- **测试连接**：向选定服务发送 `Reply with OK only.`。它会使用真实服务，可能计费。
- **设为默认模型**：选定生成使用的配置；重命名模型不会改变它的凭据身份。

当前实现的是 Chat Completions 和 Ollama Generate。Anthropic 原生 Messages、OpenAI Responses 及图片输入没有实现；兼容服务的私有协议扩展也需要单独适配。

### 长期保存的行为

模型列表、地址、模型 ID、默认项、Prompt 与偏好设置使用 IDEA `PersistentStateComponent` 保存到 IDE 配置目录下的 `options/gitCommitForAI.xml`，点击应用后由 IDE 管理落盘及重载。

API Key 单独交给 IDE **PasswordSafe**。要让 Key 在重启后继续可用，请在 **Settings → Appearance & Behavior → System Settings → Passwords** 选择持久存储。若 IDE 仅在内存保存密码，插件会提示先开启持久存储，再保存非空 Key。

- Key 输入框留空会保留之前保存的 Key；点击“清除已保存 Key”并应用才会删除。
- 复制模型配置不复制 Key，新配置需单独填写。
- 配置 JSON 导入/导出包含模型和模板，不包含 Key。导入会替换当前草稿，应用后生效。
- 导出会保留你自己填写的地址、模型名和附加参数；请勿把密钥手动写入这些普通字段。
- 模板等 Unicode 字段在 XML 中使用 UTF-8 Base64 编码，以避免 emoji 在 IDE XML 清理过程中丢失；这不是加密。JSON 导出仍是正常可读的文字。
- “永久保存”指正常应用及重启后保留；删除 IDE 配置、重装时不迁移配置或删除 PasswordSafe 数据仍会导致丢失，建议导出备份。

## Prompt 工作台

“Prompt 模板”页提供模板列表、编辑器、占位符按钮和示例预览，界面继承 IDEA 的明暗主题。

内置四种风格：**简洁 Conventional、极简单行、清晰变更摘要、Gitmoji**。支持添加、复制、删除、选择默认模板和补充缺失的内置模板。

![Prompt 工作台预览；实际 IDEA 界面继承 IDE 主题](previews/prompt-templates.png)

| 占位符 | 含义 |
| --- | --- |
| `{diff}` | 本次选中的变更，必填 |
| `{language}` | 输出语言，可自行输入 |
| `{project}` | 项目名称 |
| `{branch}` | 分支信息 |

示例：

```text
请用 {language} 根据以下变更生成简洁的提交信息。
首行使用 type(scope): 描述；必要时空行补充两条要点。
只描述变更中有依据的事实，不解释，不使用代码块。

{diff}
```

预览使用内置示例，不向模型发送请求。若启用项目模板优先级，项目根目录中的 `commit-prompt.txt` 会覆盖所选模板；文件也必须包含 `{diff}`。

## 生成提交信息

1. 在 IDEA 的 **Commit** 工具窗口勾选本次要提交的文件或变更。
2. 点击提交信息框旁的 **AI 生成提交信息** 按钮。
3. 文本逐步写入提交信息框，完成后可自行编辑，再正常提交。
4. 生成时再次点击 AI 按钮可取消；后台进度条也可取消。错误或取消会尝试恢复原提交信息。检测到手动编辑时停止写入，保留你的编辑。

旁边的齿轮按钮可快速切换默认模型和模板。**Tools → git commit for ai → 查看上次发送的 Prompt** 可以核对本项目最近一次输入；该内容仅在当前进程内存中保留。

生成仅填写提交信息，不自动执行 commit 或 push。勾选的代码和 Prompt 会发送至你配置的模型端点。

“生成选项”可以调整语言、字符预算、代理、项目模板和文件排除规则。默认排除 `.env`、密钥后缀、常见产物目录和部分 lock 文件。规则每行一个，支持 `*`、`**`、`?`。二进制内容不发送，超大文件与超出预算的 diff 会省略。

## 从源码构建

### 使用已安装的 IDEA，无需下载 SDK

需要 Windows、PowerShell 7、IDEA 2024.3.6 和 JDK 21。安装版 IDEA 自带的 JBR 如果包含 `javac`，脚本会自动使用。

```powershell
pwsh -File ./build-local.ps1 -IdeHome 'D:/path/to/IntelliJ IDEA 2024.3.6' -Test -Preview
```

若需指定 JDK，附加 `-JdkHome 'D:/jdk21'`。

安装包生成在 `build/distributions/git-commit-for-ai-1.0.0.zip`。`-Test` 运行本地 HTTP 模拟测试和两个独立 JVM 的配置持久化测试；`-Preview` 生成三页实际 Swing 面板的截图，示例截图使用 Nimbus 外观，不代表 IDEA 最终主题。

### 使用 Gradle

项目同时提供 IntelliJ Platform Gradle 配置。需要本机安装 Gradle 9.0+、JDK 21 和可访问依赖仓库的网络：

```powershell
gradle buildPlugin
# 或复用本机 IDE：
gradle buildPlugin '-PlocalIdePath=D:/path/to/IntelliJ IDEA 2024.3.6'
```

Gradle 远程目标为 Community 2024.3.6。本次交付使用离线 SDK 脚本构建；Gradle 下载路径尚未实际执行验证。

`smoke-local.ps1` 还提供独立的 IDEA 启动检查：配置、缓存和测试插件都放在 `build/ide-smoke`，不会安装到日常 IDE。使用 Ultimate 时测试沙箱需要合法可用的授权；本次该阶段受到沙箱文件权限与空白授权环境限制，没有完成完整 IDE 端到端验证。详见 `TEST-REPORT.md`。

## 实现说明

这是参考原插件的功能行为独立编写的新实现，使用独立插件 ID `dev.gitcommitforai.idea`，未复制原插件 JAR、字节码或反编译源代码。源码采用 MIT 许可证。IDEA SDK 和 Git 支持由用户安装的 IDE 提供，不打包进插件。
