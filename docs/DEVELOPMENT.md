# 开发指南

这份文档面向准备改代码、修打包、补文档或继续维护这个仓库的人。

如果你只是普通使用者，优先看 [安装指南](INSTALL.md)、[排错指南](TROUBLESHOOTING.md) 和 [发布包说明](PACKAGES.md)。

## 先知道这几个事实

- 这是一个持续维护中的 LizzieYzy 分支，不是一次性补丁仓库。
- 当前最重要的用户链路是：能装、能开、能通过 **野狐昵称** 获取最新公开棋谱、能正常分析。
- 项目已有 Java 回归、发布脚本和多平台 CI 门禁；真实 GUI、显卡后端和签名安装包仍需对应平台手工验收。

## 本地构建

### 方案一：使用系统自带 Java / Maven

只要你本机有可用的 Java 和 Maven，就可以直接构建：

```bash
mvn -B -DskipTests package
```

### 方案二：使用仓库里的工具缓存

维护这个仓库时，通常优先使用仓库内已经准备好的工具：

- JDK: `.tools/jdk-21/jdk-21.0.10.jdk/Contents/Home`
- Maven: `.tools/apache-maven-3.9.10/bin/mvn`

示例：

```bash
export JAVA_HOME="$PWD/.tools/jdk-21/jdk-21.0.10.jdk/Contents/Home"
export PATH="$PWD/.tools/apache-maven-3.9.10/bin:$JAVA_HOME/bin:$PATH"
mvn -B -DskipTests package
```

构建完成后，当前常用输出包括：

- `target/lizzie-yzy2.5.3.jar`
- `target/lizzie-yzy2.5.3-shaded.jar`

## 本地桌面场景验收

Linux / WSL 可使用独立 Xvfb 显示运行真实 Swing 场景，不占用 WSLg 或 Windows 日常桌面。先准备 JDK 21、Maven、`xvfb`、`xauth` 和中英文字体。

```bash
python3 scripts/run_acceptance.py --scenario search --scenario settings
python3 scripts/run_acceptance.py --scenario quick-analysis --engine /absolute/path/to/katago --model /absolute/path/to/model.bin.gz
```

省略 `--scenario` 只运行默认的 `search`、`settings`、`quick-analysis`，需要同时提供真实 Linux KataGo 与模型。`search` 复用中、英、韩文真实输入链；韩文场景还向生产 Swing 输入框发送受控组合与确认事件，验证确认输入不会提前打开功能，再通过 Enter 打开真实对局参数窗口。它不代表 Windows 原生韩文 IME 验收。`settings` 点击生产设置保存按钮，再用新 JVM 读取同一隔离配置；`quick-analysis` 从已可用的前台引擎导入 SGF，验证自动快析完成、共享引擎交回后的 visits 增长，以及快析期间用户暂停的保持。它不覆盖启动期间导入竞态或 Windows 原生文件选择器。

以下两个场景必须显式选择，不随默认场景运行：

```bash
python3 scripts/run_acceptance.py --scenario sgf-ui
python3 scripts/prepare_cpu_engine_acceptance.py --root target/acceptance/cpu-assets
python3 scripts/run_acceptance.py --scenario real-cpu-engine \
  --engine /absolute/path/from/manifest/to/katago \
  --model /absolute/path/from/manifest/to/model.bin.gz \
  --engine-config /absolute/path/from/manifest/to/default_gtp.cfg \
  --engine-manifest target/acceptance/cpu-assets/manifest.json
```

`sgf-ui` 用真实菜单、Swing 文件选择器和键盘操作完成打开、分支导航、受控 Java GTP 分析、另存为、取消覆盖、退出及新进程重开。它校验整棵 SGF 树的语义保留；重开定位遵循 `loadSgfLast` 的主分支语义，不要求保留保存前选中的旁支。

[Acceptance integration](../.github/workflows/acceptance-integration.yml) 在验收代码或资源目录变更时运行上述两个专项，也支持手动执行。它在独立 Linux/Xvfb 环境下载并核验目录锁定的 Eigen 引擎及模型，再保存真实 UI、GTP 和清理证据；不代表 Windows 原生桌面或自编译 GPU 后端已经验收。两平台脚本 CI 另行验证准备器和结果判定器的失败路径。

CPU 准备器按 `src/main/resources/katago-assets.json` 校验 Linux Eigen 引擎归档和模型，提取归档内的配置，并输出包含实际路径及哈希的 `manifest.json`。上例三个资源路径必须取自该 manifest；已有 manifest 的输出根目录会被拒绝。准备阶段可能联网，运行阶段不下载资源。`real-cpu-engine` 通过生产引擎管理器启动真实 KataGo，验证规则和快照盘面、当前节点正访问量、停止静默窗口以及进程／reader／临时 SGF 清理。

这是功能验收，不是吞吐基准。默认旗舰 Transformer 在托管 Eigen CPU 上曾耗时约 83 秒才输出零访问量根节点候选，因此冷搜索预算为 300 秒，整个探针上限为 450 秒；仍必须收到合法候选的正访问量并写入冻结的当前节点，零访问量、超时或跳过均不能通过。原始 GTP trace 和 `cpu-timing.json` 分别保留首批候选、首个有效搜索的证据，不修改模型、归档配置或正式应用的等待参数。

每个资源下载的 socket 等待上限为 30 秒，传输预算为 600 秒；在每次读取前后检查预算，已开始的单次读取最多再等待一个 socket 时限。停滞或超预算会失败并移除 `.part`，不会发布该资源或 manifest；已有校验通过的缓存仍可复用。

离线验收需要将整个 runner、应用和引擎进程树置于已验证的断网环境，使用相同资源和新的 `--output` 目录再执行一次；普通运行成功本身不证明离线可用。Linux/Xvfb 结果不代表 Windows/macOS 原生文件选择器或最终发布包验收。

每次运行创建新的 `target/acceptance/run-*`，保留 `acceptance.json`、Maven/JUnit 日志、子 JVM 配置、阶段记录和窗口截图。`--output` 可指定不存在的证据目录；`--timeout` 限制整体耗时。缺少引擎资源为 BLOCKED，缺少或跳过必跑用例不会成为 PASS。摘要记录源码 SHA 和工作树状态；未提交源码的本地结果不是已提交 Windows 候选的验收证明。

同一 checkout 的 Maven 调用应串行运行。此入口不改变现有 CI 分组，也不替代原生 Windows、GPU、IME 或发布包验收。

## 本地一键 CI 预检

提交前建议运行与 GitHub Actions 同源的本地预检。它会校验 JDK 21、执行完整
Maven `verify`、打包辅助脚本、换行和链接检查，并把准确的 JUnit 数量写入
`target/local-ci/`。

Windows：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run_local_ci.ps1 -Profile All
```

macOS / Linux：

```bash
bash scripts/run_local_ci.sh --profile portable
```

推送前的干净工作树复核：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run_local_ci.ps1 -Profile All -RequireClean
```

可用 profile 为 `Windows`、`Portable` 和 `All`；加 `-DryRun` / `--dry-run`
可只查看计划执行的步骤。`LIZZIE_PYTHON`、`LIZZIE_MAVEN`、`LIZZIE_BASH`
和 `LIZZIE_POWERSHELL` 可用于指定工具路径。

按职责选择 `--group all|repository|scripts|java|desktop|engine-process`（PowerShell 为
`-Group All|Repository|Scripts|Java|Desktop|EngineProcess`），默认 `all` 保持原完整 headless 调用，
不包含 `desktop` 或 `engine-process`。所有组都需要 Python 和 Git，并执行 `git diff --check`；
`--require-clean` / `-RequireClean` 保留运行前后的干净工作树检查。

| Group | Portable | Windows | 额外工具 |
| --- | --- | --- | --- |
| `repository` | 换行自测、换行、Markdown 链接 | 换行 | 无 |
| `scripts` | Python 辅助脚本、KataGo shell、Bash 语法、Linux 主机上的产品 runner fixture | JCEF、NVIDIA、RTX50 PowerShell 语法、原生 CI 进程监督检查 | Portable 需 Bash；Linux 另需 `unshare`、`ip`、`nft` 和可用的非特权 user/network namespace；Windows 需 PowerShell 7 |
| `java` | 原完整 Maven verify | 凭据专项，再执行原完整 Maven verify | Maven、JDK 21；不查找 Bash/PowerShell |
| `desktop` | 生产窗口导航 / 搜索输入 / 引擎进程生命周期 / 解说与 SGF 保存测试 | 相同显式测试选择；不代表 Windows 原生 UI 验收 | Maven、JDK 21、可用显示环境；Linux 使用 Xvfb |
| `engine-process` | `ENGINE_PROCESS_REQUIRED_TESTS` 中的 23 个方法 | 相同 23 个方法的原生 Windows 执行 | Maven、JDK 21、可用显示环境；Linux 使用 Xvfb |

例如仅运行无 Java 的仓库检查：

```bash
bash scripts/run_local_ci.sh --profile portable --group repository --summary-dir target/local-ci/portable-repository
```

```powershell
pwsh -File scripts/run_local_ci.ps1 -Profile Windows -Group Scripts -SummaryDir target/local-ci/windows-scripts
```

`profile=all` 合并并去重两套检查，只执行一次 Windows 全量 verify 和凭据专项，
不表示跨 OS 验收。不要在同一 checkout 并行运行两个写入 `target` 的 Maven 调用；
需要并行时使用独立 worktree。

多次分组调用请使用不同 summary 目录；默认仍为 `target/local-ci/`。
JSON/Markdown 摘要增加 `group`；非 Java 组不清理或读取既有 JUnit 报告，
Java/JUnit 状态为未执行。真实 Java 调用先清除旧 Surefire/Failsafe 报告，再收集本次结果。
Dry-run 仅生成计划，不代表检查通过。

Portable 脚本组逐文件执行 Bash 语法检查，失败步骤直接显示脚本路径。
Linux 主机上的 `portable` / `all` 脚本组同时运行 Linux 和 macOS 产品 runner 的受控进程 fixture。
Linux fixture 实际创建私有 network namespace，并用 `nft` 阻断和计数非 loopback 流量；
仅能执行 `unshare ... true` 不足以证明环境可用，namespace 内还必须能运行 `ip link set lo up` 和创建 nft table。
Ubuntu 的工具由 `util-linux`、`iproute2`、`nftables` 提供；CI 会安装这些依赖并先检查隔离能力，
缺少工具或权限时检查失败，不静默跳过。fixture 通过不替代最终 ZIP/DMG、真实窗口或真实引擎/GPU 验收。
两平台 Java gate 使用 Failsafe 默认命名发现集成测试，不再限定单一类；集成测试为空会失败。
执行器还检查本次 XML 中 `LoggingProviderSmokeIT.shadedArtifactWritesOneProviderEvent`
确实成功执行：关键用例缺失，或关键类任一用例 skipped/失败/报错，均使预检失败并写出失败摘要。
其他需要显示环境的可选用例仍可在 headless gate 跳过；不固定全仓测试或 skip 数量。

`ConfigReadFailurePreservationTest` 与 `WorkDirectoryReadFailurePreservationTest` 使用真实文件权限，
并先确认读取或创建文件确实被拒绝；不会因为权限限制未生效而跳过。Linux/macOS 应使用普通用户运行，
Windows 使用临时文件的 ACL 并在测试后恢复。Linux 容器中以 root 运行时，需移除绕过文件权限的能力，例如：

```bash
setpriv --bounding-set=-dac_override,-dac_read_search --no-new-privs \
  mvn -B -Djava.awt.headless=true \
  -Dtest=ConfigReadFailurePreservationTest,WorkDirectoryReadFailurePreservationTest test
```

以 root 运行完整 `verify` 时也使用同样的 `setpriv` 前缀。

`desktop` 必须有可用显示环境且 `java.awt.headless=false`；缺失显示、关键用例缺失或 skipped/失败/报错均失败。
Linux 安装 `xvfb xauth fonts-dejavu-core fonts-noto-cjk fonts-thai-tlwg` 后运行：

```bash
LANG=C.UTF-8 LC_ALL=C.UTF-8 xvfb-run -a -s '-screen 0 3840x2160x24 -nolisten tcp' bash scripts/run_local_ci.sh --profile portable --group desktop --summary-dir target/local-ci/portable-desktop
```

该组的强制用例包括以下检查，完整方法列表见 `scripts/run_local_ci.py`：

- `FunctionSearchNavigationTest.navigationPreservesRealStateAcrossNativeAndCustomMenus`
- `ConfigDialog2NavigationTest.blackWinrateRemainsReachableAcrossRebuildsAndRecreation`
- `EngineProcessSmokeTest.restoresSnapshotAnalyzesAndQuits`
- `FunctionSearchInputTest.chineseInputChain`
- `FunctionSearchInputTest.englishInputChain`
- `FunctionSearchInputTest.koreanInputChain`
- `EngineGameParametersDialogTest` 的韩文输入标签、Tab/Enter/Esc、确认等待与失败重试场景
- `SetKataEnginesHitTest` 的专用 PDA 字体布局、键盘操作、非有限数拒绝、原配置和引擎状态保留场景
- `MessageThemeTest` 的韩文、中文、英文和 HTML 提示完整显示及 Esc 关闭场景
- `TeacherDialogRequestOwnershipTest` 的全部解说请求、修正、取消和 SGF 保存场景
- `OfficialStrongestModelDesktopTest` 的真实模型目录刷新、手动选择、失效响应回退和已有引擎配置保留场景
- `HumanSlStrengthDialogTest` 的手动选择、设置重启保留、显式补充、取消和换谱结果隔离场景
- `SavedGameLoadDesktopTest` 的保存列表按钮、缩略图和放大预览打开失败保留当前棋谱、原位置与注释，并在修复同一文件后成功重试的场景
- `SavedGameSaveDesktopTest` 的真实保存按钮在磁盘任务等待期间保持窗口响应、完成后才发布 SGF/缩略图/索引并刷新列表的场景
- `ThemeSaveDesktopTest` 的主题保存与颜色重置失败保留窗口、编辑值和原文件，并在恢复写入权限后成功重试的场景
- `AnalysisProgressDesktopTest` 的分析进度窗口仍打开时实时绘制胜率与目差曲线，并在最后结果到达后完成窗口收尾的场景

分析进度用例使用真实 Swing 窗口和图表，以受控 JSON 结果代替引擎计算；它在 17 个结果中只送入 8 个时
检查已绘制的曲线点，避免把完成后的重绘误认为实时刷新。韩文窗口和曲线截图保存在
`target/desktop-smoke/probes/analysis-progress-*/`；该用例不代表真实 GPU 吞吐或远程服务验收。

主题用例使用真实设置按钮、错误对话框和目录写入权限。颜色重置在第一次编辑保存成功后禁写目录，
验证第二次真实主题写入失败不会关闭或重建设置窗口。韩文失败与重试截图保存在
`target/desktop-smoke/probes/theme-*/`；以 root 运行时也须使用上述 `setpriv` 前缀。

保存列表用例在独立子 JVM 中运行实际应用，使用真实 SGF 文件、有效缩略图及生产错误对话框。
它覆盖缺失和无效 SGF，确认失败后列表仍可操作，成功后才恢复保存位置并关闭列表。
韩文失败与重试成功截图保存在 `target/desktop-smoke/probes/saved-game-load-*/`。

保存写入用例通过阻塞共享保存 worker 控制慢磁盘时序，保留生产按钮、SGF/缩略图/索引写入和 EDT 完成处理。
它确认等待期间按钮禁用、列表与文件不提前更新，释放后才发布成功；韩文等待与完成截图保存在
`target/desktop-smoke/probes/saved-game-save-*/`。该检查验证线程与完成顺序，不是磁盘吞吐基准。

官方最强模型用例通过本地 HTTP 服务返回受控元数据，点击生产窗口的刷新按钮并等待真实异步回调。
它覆盖新增模型和命中已固定模型两种显示，验证当前模型、推荐卡片及配置文件不变；不下载或运行模型。
韩文窗口截图保存在 `target/desktop-smoke/probes/official-strongest/`。

HumanSL40 用例在独立子 JVM 中打开实际应用。受控 policy worker 只替代计算，仍经过生产准备队列、
进度窗口和 EDT 完成处理；它验证选择模型不启动计算、后台默认关闭、设置保存与重启，以及
取消、Escape、关闭、换谱和自动刷新时保留当前统计页。韩文截图保存在
`target/desktop-smoke/probes/humansl40-*/`。真实 CPU policy 补充与离线缓存验证的显式入口见
[HumanSL40 cookbook](cookbook/humansl40-strength.md)；合成输入和受控 worker 均不证明实际棋力精度。

解说用例运行真实 `TeacherDialog` 和 API-key 客户端，以本地 HTTP/SSE 服务代替收费模型。
它们验证冻结证据、一次有界修正、光标导航后的原节点保存、后续问题的原始上下文，以及
取消、换谱、节点或实战续走编辑、模态设置和 EDT 排队回调的失效处理。
成功与换谱拒绝状态的截图保存在 `target/desktop-smoke/probes/teacher/`。
该验收验证显示与保存边界；坐标、百分比和数值措辞检查不能证明所有自然语言棋理都正确。

两个搜索输入用例在独立的 zh_CN/native 和 en_US/custom 子 JVM 中，通过真实 Robot 输入覆盖工具栏、
`Ctrl+K`、查询、方向键、Enter、Escape、文本组件所有权和模态窗口边界。引擎进程用例在独立子 JVM 中
通过生产 `EngineManager` 启动受控 Java GTP peer，验证 removed-stone `SNAPSHOT`、真实 MOVE/PASS tail、
当前节点分析、停止、正常退出以及 owned process/reader/临时 SGF 清理。该 peer 的直接本地文件访问只使用
测试专用信任 seam；不证明自动 transport classification 或 remote/WSL/SSH 路径。任一必需用例缺失、
skipped、失败或报错都会使实际 runner 失败；失败摘要仍保留本次已收集的测试、失败、报错和跳过计数。
每个子 JVM 的独立工作目录、配置、应用日志、stdout/stderr、locale（如适用）、阶段、场景结果和设置前后
证据保留于 `target/desktop-smoke/probes/`。
探针默认等待最多 90 秒；超时后分别尝试有界 `jcmd` 栈和截图，再终止所拥有的进程，不依赖 EDT 响应。
诊断不可用会记录失败，不掩盖探针失败。父进程不创建子进程输出读取线程。
B11 原生窗口检查将应用启动与功能验证分开计时：启动最多 120 秒，主窗口显示且初始 EDT
清理完成后，四种缩放下各自的七语言验证再有 120 秒。启动耗尽全部验证预算不属于布局失败；
这不是启动性能 SLA，也不通过预热字体或跳过语言来掩盖慢启动。准备信号只转换一次期限，
重复信号或阶段日志不能延长验证；启动卡住、验证卡住或缺少准备信号仍失败并清理所属进程。
`phases.log` 区分应用启动、准备完成、模型边界和逐语言检查，超时仍保留线程栈与截图。
PDA 测试先观察测试父窗口打开，再显式请求该窗口获得焦点，不依赖窗口管理器自动激活新窗口。
布局检查等待窗口打开；实际键盘输入及错误对话框关闭后的焦点恢复仍验证真实焦点事件。
desktop 的新鲜 XML 单独放在 `target/desktop-smoke/surefire-reports/`；只清除该 lane 的旧报告，保留探针日志。
原 headless `all` / `java` 契约及其报告保持不变；headless Java 报告与 desktop 报告互不替代，
所有调用仍须遵守单 checkout 单 Maven 写入约束。
这证明 Linux/Xvfb 中的生产窗口导航和搜索输入链，不替代 Windows DPI/IME/theme、原生
Windows/macOS 快捷键行为或引擎/GPU 验收。

`engine-process` 单独执行 `scripts/run_local_ci.py` 里 `ENGINE_PROCESS_REQUIRED_TESTS` 的 23 个方法。
它不包含 Robot 输入用例，且将 XML 与探针证据分别写入
`target/engine-process-smoke/surefire-reports/` 和 `target/engine-process-smoke/probes/`，不会消费
`desktop` 或 headless Java 的报告。这 23 个方法必须全部成功；显示缺失、方法缺失、skip、失败或报错均失败。
Linux 本地运行：

```bash
LANG=C.UTF-8 LC_ALL=C.UTF-8 xvfb-run -a -s '-screen 0 1920x1080x24 -nolisten tcp' bash scripts/run_local_ci.sh --profile portable --group engine-process --summary-dir target/local-ci/portable-engine-process
```

Windows 必须在带原生桌面会话的 Windows 主机中运行：

```powershell
pwsh -NoProfile -File scripts/run_local_ci.ps1 -Profile Windows -Group EngineProcess -SummaryDir target/local-ci/windows-engine-process
```

设置 `java.awt.headless=false` 不会替代可用显示；显示不可用时 runner 必须失败，不能以 skip 通过。


Actions 的 `ci.yml` 对所有 PR（包括仅文档改动）、main push 和 merge_group 执行
`repository-checks`、`script-tests`、`windows-script-tests`、`java-linux`、`java17-web`、
`java-windows`、`desktop-smoke`、`analysis-provenance-ui`，以及 `engine-process` 的
Linux/Xvfb 与原生 Windows 两个 matrix leg。
Windows 脚本 job 依次运行 `windows/repository` 与 `windows/scripts`，摘要独立上传。
两平台 Java job 保留全量测试、`LoggingProviderSmokeIT`、shaded JAR 和 JaCoCo；
验证成功但 coverage artifact 缺失仍失败。各组失败时仍尝试上传本组摘要；两个进程 leg 还始终上传
`target/engine-process-smoke` 下的报告、日志和探针证据。

`ci-required` 要求上述八项、`engine-process` matrix 及选中的 Fox/B11/native Windows/SGF-CPU 补充检查成功。
失败、取消、必需执行的跳过、缺失或未知结果均不能通过；只有成功的文档专用判定允许补充检查跳过。
发布仍仅接受目标 SHA 的完整 `ci.yml` push 成功运行。

该检查是否作为合并条件强制执行，取决于目标仓库的分支保护或 ruleset 设置；仅定义 workflow
不会自动启用分支保护。fork 的设置需要单独确认，不能从上游仓库的保护状态或检查名称推断。

建议采用以下 main 合并策略；这是待仓库维护者单独启用的设置，不会随文档或 workflow 合并自动生效：

- 为 `main` 要求 `ci-required`，检查来源限定为 GitHub Actions；启用合并前与最新 main 同步的严格检查。
- 将规则同时应用于管理员，不配置日常绕过。要求通过 PR 合并；本提案不增加审批人数、签名或线性历史要求。
- 先确认修复后的 PR 在当前 head/测试合并提交上通过，再合并修复并等待该 main SHA 的 push CI 成功，然后启用规则。
- 启用前后重新读取 [main 状态](https://api.github.com/repos/22nsuk/KG-next/branches/main) 和
  [ruleset 列表](https://api.github.com/repos/22nsuk/KG-next/rulesets)，在仓库设置和 PR 合并框确认
  目标、检查来源、严格模式和管理员适用范围。权限错误或无法读取不能解释为“没有保护”。
- Fox、B11、Native Windows Engine Acceptance 和 Acceptance integration 通过 workflow_call 接入 CI，不再独立按 PR 路径触发。
  仅常规文档文本 PR 可跳过这四个补充检查；其他变更及 main push/merge_group 全部执行。
  不要将可跳过的补充检查单独设为必需状态。规则模板、启用与回退步骤见
  [CI 合并策略](CI_MERGE_POLICY.md)。提交模板不会自动启用服务器规则。

2026-10-28 检查 `22nsuk/KG-next` 的 main `23a648d5` 时，`protected=false`，ruleset 列表为空；
这是当时的状态记录，实际启用前必须重查。设置语义参见 GitHub 的
[分支保护说明](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches)。

回退 workflow 时必须保留实际验证基础组、两个进程 leg 及所需补充检查的 `ci-required`；不能直接回退到
缺少该检查的版本。偶发准备失败先保留失败日志，再重跑失败 job；不能用跳过测试、扩大超时或关闭保护代替定位。
若 workflow 与保护设置确实需要协调变更，先记录原设置、原因和恢复条件，由维护者单独处理。

### Fox Linux 依赖下载

Fox 与基础 CI 共用 `scripts/prefer_ubuntu_apt_mirror.py`，仅在 GitHub-hosted Ubuntu x64 上调整
现有官方镜像的显式优先级，保留 Azure 与 security fallback；不新增源或绕过 APT 校验。
helper 失败时准备步骤直接失败。helper 及其回归测试变更也会触发 Fox 的 Linux/Windows 两个平台检查。

准备日志输出实际 `Acquire::Retries`、HTTP/HTTPS timeout，继承 runner 的下载策略，不另外增加重试循环。
2026-10-28 的托管 runner 配置为重试 1 次、HTTP/HTTPS 超时各 15 秒；以当次日志为准。
通信超时不等于整个下载的总时长，因此 Fox 为准备步骤另设 5 分钟总预算，保留原有 15 分钟 job 上限，
为编译和实际 UI 探针留出时间。包列表、Windows 原生执行和拒绝 skipped 探针的规则均保持不变。

本地预检用于在推送前尽早发现问题，不能代替 GitHub Actions 上的干净 Windows 和
Ubuntu runner，也不能代替 macOS 签名、公证与多平台发布资产审计。

如果你改了打包、引擎路径、首次启动流程、野狐抓谱流程，建议再做对应平台的手工验证。

### Windows Java 停滞取证

`java-windows` 使用 `scripts/run_windows_ci_diagnostics.ps1` 包裹原有完整 Java 检查，
不重试失败用例，也不把抓栈成功视为测试通过。job 上限仍为 30 分钟：第一步记录
27 分钟的绝对截止时间，监督器取它与自身 23 分钟预算的较早值，运行步骤另设
25 分钟上限，为摘要与 artifact 上传留出时间。

测试 JVM 通过 test-scope JUnit listener 逐条刷新 `PLAN_STARTED`、`START`、`FINISH`、
`SKIPPED`、`PLAN_FINISHED` 事件，包含 PID、时间、JUnit unique ID 和用例名称。
只有设置了 `LIZZIE_CI_EVIDENCE_DIR` 才启用；普通本地测试不写取证文件。
监督器仅给受监督进程设置该变量，读取完整 JSONL 行并按 PID 跟踪嵌套测试计划。
存在活动计划时，控制台输出不算测试进展。

默认 120 秒无进展后，保留两次相隔 30 秒的进程/CPU 快照，并对所属 JVM 调用
`JAVA_HOME/bin/jcmd.exe Thread.print -l`，单次最多等待 10 秒。第一次停滞取证不终止
任务，同一段未恢复的停滞不重复抓取；到执行预算前也会尝试取证。截止时终止所属
Windows Job Object，独立写出失败摘要，即使 Python 执行器尚未生成自己的摘要。
命令正常完成则保留退出码；命令退出后仍留有子进程会失败并清理，不误报成功。

Actions 始终尝试上传 `windows-java-diagnostics`（保留 14 天），包含：

- `target/ci-diagnostics/summary.json`：退出状态、预算、运行身份及进程清理结果。
- `console.stdout.log`、`console.stderr.log` 和 `junit-<pid>.jsonl`：原始控制台与用例事件。
- `snapshot-<episode>-<1|2>.json` 和成功抓取的 `threads-<episode>-<1|2>-<pid>.txt`。
- `target/surefire-reports`、`target/failsafe-reports` 中已落盘的测试报告。

先从 JSONL 找最后启动但未结束的用例，再比较两份线程栈与 CPU 时间。`jcmd` 缺失或
失败会记入快照，仍保留第二份进程快照和其余证据。原始日志没有通用秘密脱敏；
不要让测试输出凭据。runner 丢失、强制取消或磁盘写入失败时，不能保证摘要及上传完成。

本地需要 Windows、PowerShell 7、Python、Maven 和 JDK 21。在干净工作树运行：

```powershell
pwsh -NoProfile -File scripts/run_windows_ci_diagnostics.ps1 -OutputDirectory target/ci-diagnostics-local
```

输出目录必须不存在或为空；再次运行请换新目录，避免混入旧证据。
原生监督器回归检查由 `windows/scripts` 组执行，无需启动 Maven 或 JDK。


## 换行规则

- 仓库中的文本文件统一使用 LF，不受开发者操作系统或 `core.autocrlf` 配置影响。
- 只有 Windows 命令脚本 `.bat`、`.cmd` 在工作区使用 CRLF；PowerShell、Shell、Java、Python、Markdown 和资源文件均使用 LF。
- `.gitattributes` 是 Git 层面的最终规则，`.editorconfig` 用于让常见编辑器在保存时提前遵守规则。
- 日常功能 PR 不要运行全仓 `git add --renormalize .`，也不要混入无关的 `fmt:format` 改写。
- CI 会运行 `python3 scripts/check_line_endings.py`，发现混合换行或错误行尾时直接失败。

## 仓库结构速览

### 代码目录

- `src/main/java/featurecat/lizzie/gui`
  - 主要界面、对话框、窗口、交互逻辑
- `src/main/java/featurecat/lizzie/analysis`
  - 引擎集成、分析流程、远程连接、野狐抓谱相关逻辑
  - 包括 `GetFoxRequest.java` 这类和野狐请求直接相关的实现
- `src/main/java/featurecat/lizzie/rules`
  - 棋盘、落子、SGF / GIB 解析、局面数据结构
- `src/main/java/featurecat/lizzie/util`
  - 常用工具类
- `src/main/java/featurecat/lizzie/theme`
  - 主题相关逻辑

### 资源目录

- `src/main/resources/l10n`
  - 多语言文案资源，例如 `DisplayStrings_zh_CN.properties`
- `src/main/resources/assets`
  - 内置资源、工具依赖、辅助素材
- `theme/`
  - 主题资源文件

### 打包与发布相关目录

- `scripts/`
  - 打包脚本、工具脚本
- `runtime/`
  - 打包时用到的运行时内容
- `engines/`
  - 内置引擎相关文件
- `weights/`
  - 默认权重文件
- `dist/`
  - 打包输出相关目录

### 文档与仓库配置

- `docs/`
  - 安装、排错、维护、发布、开发等文档
- `.github/`
  - CI、issue 模板、PR 模板、release 配置
- `assets/`
  - GitHub 项目页使用的视觉素材

## 常见改动从哪里开始

### 1. 改野狐抓谱或 野狐昵称 相关流程

优先看：

- `src/main/java/featurecat/lizzie/analysis/`
- `src/main/java/featurecat/lizzie/gui/`
- `src/main/resources/l10n/`

除了代码本身，还要确认：

- 界面里仍然写的是“野狐昵称 / Fox nickname”
- README 和安装文档没有回到旧的 UID / 用户名说法
- 至少做一轮真实抓谱验证

### 2. 改界面文案、多语言、菜单入口

优先看：

- `src/main/resources/l10n/DisplayStrings*.properties`
- `src/main/java/featurecat/lizzie/gui/`

建议同时确认：

- 中文、英文术语一致
- `with-katago`、`nvidia`、`without.engine` 拼写保持一致
- 用户可见文案变化是否需要同步 README 或安装文档

### 3. 改打包、内置引擎、发布资产

优先看：

- `scripts/prepare_bundled_runtime.sh`
- `scripts/prepare_bundled_katago.sh`
- `scripts/package_release.sh`
- `scripts/package_macos_dmg.sh`
- [发布检查清单](RELEASE_CHECKLIST.md)

这类改动通常还要同步：

- `README.md`
- `docs/PACKAGES.md`
- `docs/TESTED_PLATFORMS.md`
- GitHub Releases 说明

## 当前打包脚本分工

- `scripts/prepare_bundled_runtime.sh`
  - 准备带运行时的整合包内容
- `scripts/prepare_bundled_katago.sh`
  - 准备内置 KataGo 和默认权重
- `scripts/package_release.sh`
  - 生成 Windows / Linux / 进阶 zip 包
- `scripts/package_macos_dmg.sh`
  - 生成 macOS `.dmg`
- `scripts/validate_release_assets.sh`
  - 检查 `dist/release/` 里是否只剩普通用户应该看到的主资产
- `scripts/check_markdown_links.py`
  - 检查本地 Markdown 链接是否失效

## 提交前建议再看一眼

- 用户主链路有没有被影响：安装、启动、野狐昵称抓谱、分析
- 文案有没有回到旧的 UID / 用户名说法
- 发布包名、README、文档是否仍然一致
- 如果改了打包流程，是否补了对应说明和验证记录
- 如果改了界面，是否应该补截图

## 建议搭配阅读

- [贡献指南](../CONTRIBUTING.md)
- [维护说明](MAINTENANCE.md)
- [发布检查清单](RELEASE_CHECKLIST.md)
- [发布包说明](PACKAGES.md)
- [已验证平台](TESTED_PLATFORMS.md)
