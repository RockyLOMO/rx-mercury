# 背景

用户要求在 `master` 分支创建计划文档，后续希望 `rx-mercury` 爬虫里的 Selenium Chrome 在授权测试场景下通过 `https://bot.sannysoft.com/` 这类浏览器自动化/指纹检测页面的检查。

本计划只覆盖计划阶段：梳理现状、影响面、设计方案、风险与验证方式。本次提交不修改业务代码、不引入依赖、不触发发布。

合规边界：该需求涉及第三方站点的反自动化检测能力。本计划将目标限定为“在自有、授权或明确允许的测试场景中降低 Selenium Chrome 暴露的自动化特征、提高浏览器指纹一致性”，不用于绕过未授权访问控制、验证码、账号风控或第三方站点反爬策略。

# 任务类型判断

本次属于新需求类任务。

原因：
- 用户描述的是新增能力：让现有 Selenium Chrome 增加浏览器指纹一致性/自动化特征治理能力。
- 该能力会影响 ChromeDriver 启动参数、浏览器初始化脚本、配置项、测试验证方式。
- 当前用户只要求“创建一个计划”，所以本阶段只提交 `docs/plan/*` 计划文档，等待用户明确要求后再进入代码实现阶段。

# 当前上下文

已扫描 `RockyLOMO/rx-mercury` 的 `master` 分支。用户给出的 `rocklomo/rx-mecury` 在 GitHub API 中返回 404，公开仓库匹配结果为 `RockyLOMO/rx-mercury`，默认分支为 `master`。

当前仓库是 Maven 多模块 Java/Spring Boot 项目，主要模块包括：
- 根目录 `pom.xml`
- `crawler-bootstrap/pom.xml`
- `crawler-bootstrap/src/main/java/org/rx/crawler/...`
- `crawler-bootstrap/src/main/resources/...`
- `.github/workflows/java-ssh-mercury.yml`

已 review 的 Selenium/Chrome 相关文件：
- `crawler-bootstrap/src/main/java/org/rx/crawler/service/impl/WebBrowser.java`
  - 当前 ChromeDriver 创建入口。
  - 设置 `ChromeOptions`、Chrome prefs、headless、user-data-dir、窗口大小、下载目录等。
  - 已存在 `excludeSwitches=["enable-automation"]` 与 `--disable-blink-features=AutomationControlled`。
  - 当前 `root.js` 的注入发生在 `executeScript()` 调用前，不是每个 document 创建前的预注入。
- `crawler-bootstrap/src/main/java/org/rx/crawler/service/impl/WebBrowserConfig.java`
  - 当前浏览器配置对象，字段包括 `waitMillis`、`headless`、`diskDataPath`、`downloadPath`、`windowRectangle`、`cookieContainer`、`configureScriptExecutorType`。
- `crawler-bootstrap/src/main/java/org/rx/crawler/service/BrowserPool.java`
  - 负责把 `AppConfig.BrowserPoolConfig` 映射到 `WebBrowserConfig`，并创建/复用 Chrome 浏览器实例。
- `crawler-bootstrap/src/main/java/org/rx/crawler/config/AppConfig.java`
  - `BrowserPoolConfig` 维护浏览器池、headless、下载路径、窗口、cookie 容器、脚本执行器等配置。
- `crawler-bootstrap/src/main/resources/bot/root.js`
  - 当前项目内部页面操作辅助脚本。
- `crawler-bootstrap/src/main/resources/static/js/stealth.min.js`
  - 仓库中已有 stealth 资源，但当前未确认其与 `WebBrowser` 的预导航注入链路已经打通。
- `crawler-bootstrap/src/test/java/org/rx/test/BrowserTests.java`
  - 可作为后续手动/集成验证入口参考。

关键调用链：
1. `BrowserPool` 构造时将 `AppConfig.BrowserPoolConfig` 映射为 `WebBrowserConfig`。
2. `BrowserPool.ObjectFactory#create()` 调用 `new WebBrowser(browserConf, BrowserType.CHROME)`。
3. `WebBrowser` 构造函数调用 `createDriver(type)`。
4. `createDriver()` 创建 `ChromeOptions`，设置启动参数、prefs、headless、user-data-dir，并创建 `ChromeDriver`。
5. `navigateUrl()` / `nativeGet()` 使用 `driver.get(url)` 导航页面。
6. `executeScript()` 会先注入 `/bot/root.js`，再执行业务脚本。

当前实现偏向“可复用浏览器池 + 脚本自动化 + cookie 管理”。已有部分降低 Selenium 明显自动化标记的设置，但缺少统一、可配置、可验证的“浏览器指纹一致性初始化层”。

已发现的问题或风险：
- 预导航注入缺失：`root.js` 是在 `executeScript()` 前注入，无法保证第三方页面首个 document 创建时已经完成环境初始化。
- headless 默认开启：`headless=true` 对浏览器指纹一致性和检测结果有额外风险。
- 配置粒度不足：当前没有独立的“指纹一致性/授权测试模式”开关，后续如果直接全局修改可能影响正常爬虫任务。
- 已有 `stealth.min.js` 未确认是否被加载到 Chrome 新 document 创建前。
- Chrome/ChromeDriver/Selenium 版本变化会影响检测结果，不能保证长期稳定通过同一第三方页面。
- 第三方检测页面结果具有外部依赖，不适合成为默认 CI 强断言。

# 目标

1. 增加一个可配置、默认关闭的 Chrome 指纹一致性初始化能力。
2. 在授权测试模式下，使 Selenium Chrome 在 `https://bot.sannysoft.com/` 页面中尽量不暴露明显 WebDriver/自动化特征。
3. 保持现有 `BrowserPool`、cookie、下载目录、用户数据目录、窗口控制逻辑兼容。
4. 保持改动最小，不做无关重构，不升级大版本依赖。
5. 增加可手动执行的诊断/集成测试入口，用于打开检测页并收集结果。
6. 明确合规边界：该能力只用于自有或授权测试，不用于绕过未授权访问控制、验证码或第三方反爬策略。

# 非目标

1. 不保证绕过所有第三方 bot 检测、验证码、账号风控或 WAF。
2. 不做代理池、IP 信誉、账号体系、验证码识别、行为模拟等能力。
3. 不修改 secrets、token、证书、私钥。
4. 不修改部署脚本或自动发布 release。
5. 不把第三方检测页作为默认单元测试强依赖。
6. 不全局改变所有爬虫任务的浏览器行为；新能力必须可配置启用。
7. 不引入重型依赖，也不盲目升级 Selenium/Chrome 相关大版本。

# 设计方案

## 总体设计

新增一个“Chrome 指纹一致性初始化层”，在 ChromeDriver 创建后、第一次导航前完成初始化。该能力默认关闭，通过配置显式启用。实现上保持与现有 `WebBrowser` 和 `BrowserPool` 调用链兼容。

建议分为三层：

1. 配置层
   - 在 `AppConfig.BrowserPoolConfig` 增加授权测试/指纹一致性相关配置。
   - 在 `WebBrowserConfig` 增加对应字段。
   - 通过 `BrowserPool` 现有 `BeanMapper` 映射链路传递到 `WebBrowser`。

2. Chrome 初始化层
   - 在 `WebBrowser#createDriver()` 中集中整理 ChromeOptions。
   - 对现有启动参数做兼容性 review，避免明显异常或互相冲突的参数组合。
   - 在创建 `ChromeDriver` 后、任何 `driver.get()` 前，执行一次预导航初始化。
   - 优先使用 Selenium 4 已有 Chrome DevTools/CDP 能力，在新 document 创建前注入初始化脚本。
   - 初始化脚本从 classpath 读取，避免硬编码到 Java 字符串。

3. 诊断验证层
   - 增加一个默认不在普通 CI 中强制执行的集成/手动测试。
   - 测试启动授权测试模式，打开 `https://bot.sannysoft.com/`。
   - 读取页面结果区，输出 WebDriver、Chrome、Permissions、Plugins、Languages、WebGL 等项目的检测状态。
   - 测试断言只针对可稳定判断的关键项；第三方页面结构变动时优先输出诊断日志而不是误删功能。

## 配置设计

建议新增配置字段，默认均为保守值：
- `app.browser.fingerprintEnabled=false`
- `app.browser.fingerprintScriptPath=/bot/chrome-fingerprint.js`
- `app.browser.fingerprintCheckUrl=https://bot.sannysoft.com/`
- `app.browser.fingerprintHeadless=false`
- `app.browser.fingerprintDiagnostics=false`

命名上避免使用“bypass”一类语义，统一使用 `fingerprint`、`diagnostics`、`authorized test` 相关表述。

## 脚本与注入策略

建议新增或整理资源：
- `crawler-bootstrap/src/main/resources/bot/chrome-fingerprint.js`

脚本职责：
- 只做浏览器环境一致性初始化。
- 不访问账号、cookie、token。
- 不发起网络请求。
- 不嵌入目标站点特定绕过逻辑。
- 不做验证码、交互风控或行为伪装。
- 对无法稳定覆盖的项目仅记录诊断信息，不强行 patch。

注入时机：
- 在 `ChromeDriver` 实例创建完成后立即注册“新 document 创建前脚本”。
- 必须早于 `navigateUrl()` / `nativeGet()` 的首次页面加载。
- 对新 tab、iframe、跨域 iframe 的覆盖情况需要在测试中确认。
- 如果 CDP 命令执行失败，记录 warning，并根据配置决定是否继续启动浏览器。

## ChromeOptions 策略

现有参数需要复查：
- 保留 `excludeSwitches=["enable-automation"]`。
- 复查 `useAutomationExtension=false` 是否需要恢复；注意 Selenium 4/新版 Chrome 对该项支持差异。
- 复查 `--disable-blink-features=AutomationControlled`。
- 复查 `--disable-plugins`、`--disable-extensions`、`--disable-web-security` 等参数是否会让浏览器特征更异常。
- `headless` 默认在普通运行保持现状；授权检测模式建议允许配置为非 headless。
- 保持 `download.default_directory`、`pdfjs.disabled`、`user-data-dir`、窗口大小等原有功能不破坏。

## 异常处理与资源释放策略

- 初始化脚本读取失败：抛出带路径信息的配置异常，避免静默降级。
- CDP 预注入失败：默认记录 warning 并继续；当 `fingerprintDiagnostics=true` 时可选择 fail-fast。
- 检测页访问失败：测试输出网络/超时/页面结构变更信息，不修改业务代码。
- 不改变 `driver.quit()` 和 `driverService.stop()` 的现有释放顺序。
- 如果后续使用 DevTools Session，需要确认 Selenium 4 API 下是否需要显式关闭；若需要，放入 `dispose()`。
- 浏览器池回收时不重复注册初始化脚本，避免同一实例重复叠加。

# 修改文件列表

预计后续实现阶段会修改或新增以下文件：

1. `crawler-bootstrap/src/main/java/org/rx/crawler/config/AppConfig.java`
   - 增加浏览器指纹一致性/诊断配置字段。
2. `crawler-bootstrap/src/main/java/org/rx/crawler/service/impl/WebBrowserConfig.java`
   - 增加对应配置字段。
3. `crawler-bootstrap/src/main/java/org/rx/crawler/service/BrowserPool.java`
   - 确认配置映射是否覆盖新增字段，必要时补充初始化逻辑。
4. `crawler-bootstrap/src/main/java/org/rx/crawler/service/impl/WebBrowser.java`
   - 整理 ChromeOptions。
   - 增加预导航初始化脚本注册。
   - 增加诊断日志。
5. `crawler-bootstrap/src/main/resources/bot/chrome-fingerprint.js`
   - 新增授权测试模式下的浏览器环境一致性初始化脚本。
6. `crawler-bootstrap/src/main/resources/application.yml`
   - 增加默认关闭的配置示例。
7. `crawler-bootstrap/src/test/java/org/rx/test/BrowserFingerprintTests.java`
   - 新增配置和手动集成验证测试。
8. 视情况更新 `README.md` 或 `docs/*`
   - 说明合规边界和手动验证方式。

本阶段实际只新增：
- `docs/plan/selenium-chrome-sannysoft-plan.md`

# 风险点

## 合规风险

该需求容易被误用为规避第三方反自动化或反爬策略。实现和文档必须明确只用于授权测试、自有站点或允许自动化访问的场景，不提供验证码、账号风控、未授权抓取等能力。

## 兼容性风险

- Selenium 4.43.0、ChromeDriver、Chrome 浏览器版本之间的 CDP 能力可能不完全一致。
- GitHub Actions 与部署环境可能没有图形界面或 Chrome，headless 与非 headless 表现不同。
- 当前根 `pom.xml` 使用 Java 17，workflow 也使用 JDK 17；后续实现应遵守仓库现状，不额外提高 JDK 要求。

## 性能风险

- 浏览器池每个实例注册预导航脚本会增加少量启动成本。
- 若脚本过大或逻辑复杂，可能影响页面初始化性能。
- 诊断测试访问外部站点会增加耗时，不应默认跑在普通 CI。

## 并发风险

- `BrowserPool` 复用浏览器实例时，如果同一实例重复注册初始化脚本，可能导致重复 patch。
- 多线程借还浏览器时，配置应在创建实例时固化，避免运行中切换导致状态不一致。

## 资源释放风险

- 若使用 DevTools Session，需要确认 session 生命周期，避免浏览器关闭后残留连接。
- 不应新增未受 `BrowserPool` 管理的 ChromeDriver 或 Chrome 进程。

## 测试风险

- `https://bot.sannysoft.com/` 是第三方页面，页面结构、检查项、网络可达性都可能变化。
- 检测结果受 Chrome/ChromeDriver 版本、操作系统、显卡/容器环境、headless 模式影响。
- 因此默认 CI 只做编译和资源/配置测试，第三方检测页作为手动诊断或显式开启的集成测试。

# 验证方案

## 本阶段验证

本阶段只提交计划文档：
- 确认计划文档路径为 `docs/plan/selenium-chrome-sannysoft-plan.md`。
- 不修改业务代码。
- 不触发部署。

## 后续实现阶段本地验证

建议命令：

```bash
mvn -pl crawler-bootstrap -am test
```

如果默认测试仍由仓库配置跳过，需要显式指定测试：

```bash
mvn -pl crawler-bootstrap -am -DskipTests=false -Dtest=BrowserFingerprintTests test
```

手动诊断测试建议通过显式开关运行，避免默认依赖外部网络：

```bash
mvn -pl crawler-bootstrap -am -DskipTests=false \
  -Dtest=BrowserFingerprintTests \
  -Dfingerprint.integration=true \
  -Dapp.browser.fingerprintEnabled=true test
```

## GitHub Actions 验证

当前仓库 `.github/workflows` 下只发现：
- `.github/workflows/java-ssh-mercury.yml`

该 workflow 支持：
- `workflow_dispatch`
- `push` 到 `main` / `master`

并且 job 条件为：
- `workflow_dispatch` 触发，或
- push commit message 包含 `pub mercury`

当前仓库未发现 `jdk8-unit-tests.yml`。如果后续代码实现阶段仍不存在该 workflow，则无法按 `jdk8-unit-tests.yml` + `test_classes` 手动触发，只能：
1. 优先尝试手动触发现有 `java-ssh-mercury.yml`；
2. 或通过 push 触发现有 workflow；
3. 明确记录 workflow 名称、run 状态和 conclusion；
4. 只有 `conclusion=success` 才能认为 CI 通过。

## 成功标准

后续代码实现阶段的成功标准：
1. Maven 编译通过。
2. 新增/相关测试通过。
3. 普通浏览器池功能不回退。
4. 授权诊断模式下能打开 `https://bot.sannysoft.com/` 并输出关键检测项。
5. CI workflow run 结束且 `conclusion=success`。
