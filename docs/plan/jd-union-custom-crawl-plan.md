# 背景

用户希望在 `master` 分支先创建计划文档，后续实现一个 JD 联盟自定义内容爬取/生成推广链接任务。

目标流程基于用户提供的页面路径：

1. 先访问 `https://bot.sannysoft.com/` 做 Chrome 浏览器自动化指纹基线检测。只有检测通过关键项后，才允许进入 JD 联盟流程；如果未通过，则停止任务并返回 `BROWSER_FINGERPRINT_CHECK_FAILED`。
2. 访问 `https://union.jd.com/proManager/shopPromotion` 做登录态检查。
3. 如果被退回到 `https://union.jd.com/index?returnUrl=`，说明需要登录，由人接管完成登录。
4. 人工登录成功后进入 `https://union.jd.com/proManager/index?pageNo=1`，再由程序继续接管。
5. 程序输入商品参数，例如 `100059484008`，点击“搜索全部商品”。
6. 理想情况下搜索到 1 个商品，点击“我要推广”。
7. 在生成推广链接弹框中固定选择：
   - 导购媒体推广
   - 所属导购媒体：微信
   - 投放推广为：选择推广位
   - 广告位名称：选择与入参对应的数字选项，例如 `5`
8. 点击“获取推广链接”。
9. 点击“复制”或读取输入框中的推广链接，返回该字符串。

用户补充：自定义内容爬取后续会很多，并且需要读写能力。因此本计划不仅覆盖 JD 联盟单个任务，也要给后续多个自定义站点/任务预留扩展结构。

合规边界：登录、短信、验证码、风控校验等均由人工完成，不在代码中绕过；代码只在用户已登录、已授权使用的账号会话中执行页面自动化和结果读取。`bot.sannysoft.com` 仅作为本地授权环境的浏览器基线诊断页，不把它作为绕过第三方访问控制的保证。

# 任务类型判断

本次属于新需求类任务。

原因：
- 这是新增一个业务自动化任务：JD 联盟商品搜索并生成推广链接。
- 需要新增任务编排、入参/出参模型、登录态判断、Chrome 用户数据硬盘持久化、页面操作流程、结果写出能力。
- 需要把 Chrome 指纹检测作为 JD 任务前置条件。
- 当前用户要求是“生成一个计划文档”，所以本阶段只提交 `docs/plan/*`，不修改业务代码。

# 当前上下文

## 已 review 的仓库现状

当前分支：`master`。

已 review 文件：

- `crawler-bootstrap/src/main/java/org/rx/crawler/service/impl/WebBrowser.java`
  - 当前 Selenium Chrome 的核心封装。
  - 支持 `navigateUrl`、`nativeGet`、`elementPress`、`elementClick`、`executeScript`、`elementVal`、`elementsText` 等页面操作能力。
  - 已支持 Chrome `user-data-dir`：读取 `config.getDiskDataPath()`，通过 `user-data-dir=` 参数传入 Chrome。
  - 当前 `diskDataPath` 会执行 `String.format(config.getDiskDataPath(), id)`，因此如果配置中包含 `%s` 会按浏览器实例生成多个目录；如果配置为固定目录则会保持单一登录态，但不适合并发多个 Chrome 实例同时使用。
  - 当前已加入 `fingerprintEnabled`、`fingerprintHeadless`、预加载 JS、CDP 注入等能力，可作为 JD 页面自动化稳定性的基础，但 JD 登录不能靠这些绕过校验。

- `crawler-bootstrap/src/main/java/org/rx/crawler/service/impl/WebBrowserConfig.java`
  - 已包含 `headless`、`diskDataPath`、`downloadPath`、`windowRectangle`、`cookieContainer`、`configureScriptExecutorType`、`fingerprint*` 等字段。

- `crawler-bootstrap/src/main/java/org/rx/crawler/config/AppConfig.java`
  - `BrowserPoolConfig` 已包含浏览器池配置、diskDataPath、downloadPath、headless、fingerprint 等配置。
  - 后续可以继续增加自定义任务配置，例如 JD 联盟登录页、搜索页、媒体名称、默认超时、结果目录等。

- `crawler-bootstrap/src/main/java/org/rx/crawler/service/BrowserPool.java`
  - 负责创建和复用 `WebBrowser`。
  - 当前池模型适合通用浏览器复用；但 JD 联盟需要稳定登录态，建议使用命名浏览器 profile，并且同一个 profile 串行执行。

- `crawler-bootstrap/src/main/java/org/rx/crawler/service/BrowserService.java`
  - 启动时设置 webdriver 路径并初始化全局 `BrowserPool`。
  - 会清理浏览器/驱动进程，后续做人工登录时要注意避免启动时误清理正在手工使用的浏览器。

- `crawler-bootstrap/src/main/java/org/rx/crawler/controller/ApiController.java`
  - 当前基本是注释掉的代理接口代码。
  - 不建议把 JD 自定义任务塞回这个旧 Controller；应新增独立 Controller，避免职责混乱。

- `crawler-bootstrap/src/main/resources/application.yml`
  - 已有浏览器默认配置和 fingerprint 配置示例。

## 关键调用链

当前浏览器能力调用链：

1. `BrowserService#init()` 初始化 webdriver 路径与全局 `BrowserPool`。
2. `BrowserPool` 将 `AppConfig.BrowserPoolConfig` 映射为 `WebBrowserConfig`。
3. `BrowserPool.ObjectFactory#create()` 创建 `new WebBrowser(browserConf, BrowserType.CHROME)`。
4. `WebBrowser#createDriver()` 创建 ChromeOptions，设置 `user-data-dir`、headless、fingerprint 等参数。
5. 业务代码通过 `Browser` 接口调用页面导航、输入、点击、执行脚本、读取字段。

## 当前实现意图

现有代码更像“通用分布式浏览器池 + 页面脚本执行服务”。JD 联盟推广链接生成属于“站点业务任务”，不应直接写入 `WebBrowser` 内部，也不应把页面流程散落在 Controller 中。

## 已发现的问题和设计约束

1. Chrome 指纹基线是 JD 任务前置条件。
   - 任务启动前需要显式打开 `https://bot.sannysoft.com/`。
   - 检测关键项包括 `navigator.webdriver`、`HeadlessChrome` UA、plugins、languages、window.chrome 等现有测试已覆盖的基础项。
   - 不通过时不进入 JD 流程，避免在 JD 页面上反复触发异常请求。
   - 第三方检测页结果会变化，因此建议提供配置化“严格/宽松”模式，并把完整页面文本和 probe 结果写入日志。

2. 登录态需要持久化到硬盘。
   - 必须使用 Chrome `user-data-dir`。
   - JD 联盟这种人工登录态建议使用固定 profile 目录，例如 `D:/app-crawler/data/chrome/jd-union`。
   - 固定 profile 目录不适合多个 Chrome 同时打开，否则会遇到 profile lock，因此 JD 任务需要串行锁或专用单实例 session。

3. 登录由人接管。
   - 如果访问 `shopPromotion` 被重定向到 `index?returnUrl=`，程序应返回 `LOGIN_REQUIRED`，同时保持浏览器打开或给出登录接管说明。
   - 人工登录完成后，可以通过接口继续同一个任务，或由操作者重新调用任务，复用硬盘 profile 中的登录态。
   - 不在代码中处理短信、滑块、验证码、扫码等登录校验。

4. 自定义内容爬取后续会很多。
   - 不建议把 JD 任务直接写死到 `WebBrowser`、`BrowserPool` 或已有 `ApiController`。
   - 第一阶段建议仍放在 `crawler-bootstrap` 模块内，因为它强依赖现有浏览器池、Spring 配置、HTTP API、启动脚本和部署方式。
   - 在 `crawler-bootstrap` 内新增独立包结构，沉淀通用任务接口和 JD 站点适配器；当后续任务数量明显增加、依赖边界稳定后，再抽出独立 Maven 模块。

5. 读写能力。
   - 入参可以支持 HTTP JSON、命令行/测试参数、CSV/JSON 文件。
   - 出参至少支持 HTTP JSON 返回，同时可写到本地 JSONL/CSV 文件，便于批量任务断点续跑。

# 目标

1. 在 `crawler-bootstrap` 内建立可扩展的自定义爬取任务结构，而不是新建 Maven 模块。
2. 新增 JD 联盟推广链接生成任务：输入商品 ID 与推广位映射，输出推广链接。
3. 任务启动前执行 Chrome 指纹基线检测，不通过则停止并返回明确状态。
4. 使用固定 Chrome user-data-dir 持久化 JD 联盟登录态。
5. 支持人工登录接管：检测到未登录时返回 `LOGIN_REQUIRED`，不绕过登录校验。
6. 支持读写：单条 HTTP 调用、批量文件输入、结果文件输出都预留设计。
7. 保持现有 `WebBrowser` / `BrowserPool` 基础能力不被业务逻辑污染。
8. 最小改动，不引入重型依赖，不升级大版本依赖。

# 非目标

1. 不绕过京东联盟登录、短信、扫码、验证码、滑块或风控校验。
2. 不保证对 JD 页面未来所有改版都自动兼容。
3. 不把推广位、账号、cookie、token、私钥等敏感信息写入仓库。
4. 不新增独立 Maven 模块，除非后续自定义任务数量和边界已经稳定。
5. 不自动发布 release。
6. 不做 IP 代理池、账号池或大规模并发请求。
7. 不在默认 CI 中强依赖 JD 联盟或 Sannysoft 外部页面可访问。

# 设计方案

## 模块归属结论

建议本次直接写在 `crawler-bootstrap` 中，不新建 Maven 模块。

原因：
- JD 任务强依赖当前 `crawler-bootstrap` 的 Spring Boot 启动、`BrowserService`、`BrowserPool`、`WebBrowser`、ChromeDriver 路径和配置体系。
- 当前仓库只有一个业务模块，贸然新建 Maven 模块会带来依赖拆分、配置共享、启动入口和部署改造成本。
- 后续自定义爬取会很多，但第一步应该先在 `crawler-bootstrap` 内建立清晰边界：通用任务接口 + 站点适配器 + DTO + Controller。
- 当后续出现多个站点、多个任务、共用任务调度、任务持久化和队列后，再把 `custom-task` 或 `crawler-task` 抽成独立 Maven 模块更合适。

建议包结构：

```text
crawler-bootstrap/src/main/java/org/rx/crawler/task/
  common/
    CustomCrawlTask.java
    CustomCrawlRequest.java
    CustomCrawlResult.java
    CustomCrawlStatus.java
    BrowserProfileManager.java
    BrowserPreflightService.java
    ResultWriter.java
  jd/
    JdUnionPromotionTask.java
    JdUnionPromotionRequest.java
    JdUnionPromotionResult.java
    JdUnionConfig.java
  controller/
    CustomCrawlController.java
```

也可以把 `controller` 继续放在现有 `org.rx.crawler.controller` 下，但业务任务类应放在 `task` 或 `custom` 包下。

## 通用任务抽象

新增通用接口：

```java
public interface CustomCrawlTask<TRequest, TResult> {
    String taskType();
    TResult execute(TRequest request);
}
```

通用结果状态建议：

- `SUCCESS`
- `LOGIN_REQUIRED`
- `BROWSER_FINGERPRINT_CHECK_FAILED`
- `NOT_FOUND`
- `MULTIPLE_MATCHED`
- `PAGE_CHANGED`
- `TIMEOUT`
- `FAILED`

所有自定义任务都统一返回：

```json
{
  "status": "SUCCESS",
  "taskType": "jdUnionPromotion",
  "input": {...},
  "data": {...},
  "message": "",
  "diagnostics": {...}
}
```

## JD 联盟请求模型

建议 `JdUnionPromotionRequest` 字段：

- `skuId`：商品 ID，例如 `100059484008`。
- `adSiteName`：广告位名称，例如 `5`。
- `mediaType`：默认 `导购媒体推广`。
- `mediaName`：默认 `微信`。
- `profileName`：默认 `jd-union`。
- `forcePreflight`：默认 `true`，是否每次先跑 Sannysoft 基线。
- `keepBrowserOpenOnLoginRequired`：默认 `true`，未登录时是否保持浏览器用于人工登录。
- `outputPath`：可选，写出结果文件。

批量输入建议支持 JSON 数组或 CSV：

```json
[
  {"skuId":"100059484008","adSiteName":"5"}
]
```

## JD 联盟返回模型

建议 `JdUnionPromotionResult` 字段：

- `skuId`
- `adSiteName`
- `promotionUrl`
- `status`
- `message`
- `currentUrl`
- `loginRequired`
- `fingerprintPassed`
- `diagnostics`

## Chrome profile 持久化设计

新增 `BrowserProfileManager`，职责：

- 根据 `profileName` 返回固定 profile 目录。
- JD 默认目录：`D:/app-crawler/data/chrome/jd-union` 或 Linux 下 `/app-crawler/data/chrome/jd-union`。
- 确保目录存在。
- 对同一 profileName 做进程内互斥锁，避免多个 Chrome 同时使用一个 profile。

注意当前 `WebBrowser#createDriver()` 会对 `diskDataPath` 执行 `String.format(path, id)`。后续实现有两种方案：

方案 A：扩展 `WebBrowserConfig`，增加 `fixedDiskDataPath` 或 `profileDataPath`。
- 优点：语义清晰，不影响现有 `%s` 池化模式。
- 缺点：需要改 `WebBrowser#createDriver()`。

方案 B：约定 JD 专用配置传入无 `%s` 的固定目录，并修改 `WebBrowser` 对无格式占位符的处理。
- 优点：字段少。
- 缺点：`String.format` 对无占位符字符串虽然可用，但 `chromeIdCounter` 与目录创建逻辑仍不够清晰。

建议采用方案 A，新增：

- `WebBrowserConfig.profileDataPath`
- `AppConfig.BrowserPoolConfig.profileDataPath`

如果 `profileDataPath` 非空，则优先使用固定 profile；否则沿用现有 `diskDataPath` 池化逻辑。

## 浏览器基线预检设计

新增 `BrowserPreflightService`：

1. 使用 JD 同一个 profile 或单独诊断 profile 启动 Chrome。
2. 确保 `fingerprintEnabled=true`，建议 `fingerprintHeadless=false`。
3. 访问 `https://bot.sannysoft.com/`。
4. 执行 JS probe 读取关键项：
   - `navigator.webdriver`
   - `navigator.userAgent` 是否含 `HeadlessChrome`
   - `navigator.plugins.length`
   - `navigator.languages`
   - `window.chrome`
   - 页面文本中明显 failed 项
5. 判断关键项是否通过。
6. 不通过则写诊断日志和截图，返回 `BROWSER_FINGERPRINT_CHECK_FAILED`。

建议预检配置：

- `app.custom.jdUnion.preflightEnabled=true`
- `app.custom.jdUnion.preflightUrl=https://bot.sannysoft.com/`
- `app.custom.jdUnion.preflightStrict=false`
- `app.custom.jdUnion.preflightCacheMinutes=30`

为了减少每次任务都访问外部检测页，可缓存最近一次通过结果，例如 30 分钟内复用。但如果 Chrome/配置/profile 变了，应重新检测。

## JD 登录态检查设计

任务流程：

1. 启动 JD 专用 Chrome profile。
2. 先执行浏览器基线预检。
3. 访问 `https://union.jd.com/proManager/shopPromotion`。
4. 等待页面加载。
5. 读取 currentUrl。
6. 如果 currentUrl 以 `https://union.jd.com/index?returnUrl=` 开头，返回：

```json
{
  "status": "LOGIN_REQUIRED",
  "message": "JD Union login required. Please finish login in the opened Chrome profile, then retry or continue.",
  "currentUrl": "..."
}
```

7. 如果页面已登录，则导航到 `https://union.jd.com/proManager/index?pageNo=1` 开始自动化。

人工登录接管策略：
- 第一阶段可以简单设计为：返回 `LOGIN_REQUIRED`，保持浏览器一段时间或不主动关闭，由用户完成登录后重新调用任务。
- 更稳定的做法是新增两个接口：
  - `/custom/jd-union/login/check`：启动并检查登录态。
  - `/custom/jd-union/promotion`：执行生成链接。
- 若保持浏览器打开，需要避免 `tryClose` 立即释放；这会影响资源管理，建议实现时明确超时和手动关闭接口。

## JD 页面操作流程

进入 `https://union.jd.com/proManager/index?pageNo=1` 后：

1. 等待搜索输入框出现。
2. 清空并输入 `skuId`。
3. 点击“搜索全部商品”。
4. 等待结果区加载完成。
5. 判断结果数量：
   - 0 个：返回 `NOT_FOUND`。
   - 多个：返回 `MULTIPLE_MATCHED`，附带候选商品标题/ID，避免误推广。
   - 1 个：继续。
6. 点击商品下的“我要推广”。
7. 等待弹框。
8. 选择“导购媒体推广”。
9. 所属导购媒体选择“微信”。
10. 投放推广为选择“选择推广位”。
11. 广告位名称选择 `adSiteName`，例如 `5`。
12. 点击“获取推广链接”。
13. 优先直接读取弹框中的链接输入框 value。
14. 如果 value 为空，再点击“复制”并读取 clipboard；但 clipboard 在浏览器权限和系统环境中不稳定，因此不作为首选。
15. 返回推广链接。

选择器策略：
- 第一版实现优先使用可读文本 XPath，例如按钮文本、label 文本、placeholder。
- 对 Ant Design / Vue / React 动态 DOM，必要时使用 JS 在页面中按文本查找按钮和下拉项。
- 所有关键选择器都集中到 `JdUnionPromotionTask` 常量或配置中，避免散落。
- 如果页面结构变化，返回 `PAGE_CHANGED` 并输出当前页面关键文本和截图路径。

## 读写设计

### HTTP 单条接口

建议新增：

```text
POST /custom/jd-union/promotion
```

请求：

```json
{
  "skuId": "100059484008",
  "adSiteName": "5"
}
```

响应：

```json
{
  "status": "SUCCESS",
  "data": {
    "skuId": "100059484008",
    "adSiteName": "5",
    "promotionUrl": "https://..."
  }
}
```

### 批量文件输入

建议新增：

```text
POST /custom/jd-union/promotion/batch
```

支持请求中传 `inputPath` 和 `outputPath`：

```json
{
  "inputPath": "D:/app-crawler/data/jd-union/input.json",
  "outputPath": "D:/app-crawler/data/jd-union/output.jsonl"
}
```

输出 JSONL，每行一个结果，便于断点续跑。

### 文件写出

新增 `ResultWriter`：

- 成功和失败都写出。
- 字段包含 `skuId`、`adSiteName`、`status`、`promotionUrl`、`message`、`createdAt`。
- 文件写入使用追加模式。
- 不写 cookie、token、页面 HTML 中的敏感字段。

## 并发设计

- JD 联盟 profile 默认单实例串行。
- 使用 `ReentrantLock` 或命名锁：`profileName=jd-union`。
- 批量任务内部串行处理，避免触发页面频控或 profile lock。
- 后续如果要多账号并发，应使用多个 profileName，例如 `jd-union-a`、`jd-union-b`，每个 profile 仍串行。

## 异常处理

建议状态映射：

- Sannysoft 基线不通过：`BROWSER_FINGERPRINT_CHECK_FAILED`
- 未登录或跳登录页：`LOGIN_REQUIRED`
- 搜索不到商品：`NOT_FOUND`
- 搜索结果不唯一：`MULTIPLE_MATCHED`
- 关键按钮/弹框找不到：`PAGE_CHANGED`
- 页面等待超时：`TIMEOUT`
- 其他异常：`FAILED`

每次失败都应记录：
- 当前 URL
- 当前任务参数
- 失败步骤
- 可选截图路径
- 可选页面文本摘要

# 修改文件列表

后续实现阶段预计新增/修改：

1. `crawler-bootstrap/src/main/java/org/rx/crawler/config/AppConfig.java`
   - 增加 `CustomTaskConfig` / `JdUnionConfig`，包含 profile 路径、预检 URL、默认媒体、结果目录等。

2. `crawler-bootstrap/src/main/java/org/rx/crawler/service/impl/WebBrowserConfig.java`
   - 视实现方案增加 `profileDataPath` 或固定 profile 相关字段。

3. `crawler-bootstrap/src/main/java/org/rx/crawler/service/impl/WebBrowser.java`
   - 最小修改 profile 路径选择逻辑，支持固定 user-data-dir。
   - 不写 JD 业务逻辑。

4. `crawler-bootstrap/src/main/java/org/rx/crawler/task/common/*`
   - 新增通用任务接口、状态枚举、profile 管理、预检服务、结果写出工具。

5. `crawler-bootstrap/src/main/java/org/rx/crawler/task/jd/*`
   - 新增 JD 联盟推广链接生成任务、请求/响应 DTO、配置类。

6. `crawler-bootstrap/src/main/java/org/rx/crawler/controller/CustomCrawlController.java`
   - 新增自定义任务 HTTP 接口。

7. `crawler-bootstrap/src/main/resources/application.yml`
   - 新增 JD Union 默认配置示例，默认关闭自动执行。

8. `crawler-bootstrap/src/test/java/org/rx/test/JdUnionPromotionTaskTests.java`
   - 新增单元测试和显式开启的手动集成测试。

9. `docs/*` 或 `README.md`
   - 说明人工登录、profile 目录、输入输出格式、失败状态。

本阶段实际只新增：

- `docs/plan/jd-union-custom-crawl-plan.md`

# 风险点

## 合规风险

JD 联盟登录和风控必须由人工完成。代码只做登录后授权页面内的自动化操作。不得写绕过验证码、短信、扫码、账号风控的逻辑。

## Chrome 指纹风险

- `bot.sannysoft.com` 只是诊断页，不代表所有页面都一定放行。
- 检测页本身可能改版，不能作为默认 CI 强依赖。
- 预检不通过时继续访问 JD 可能触发更多风控，因此必须 fail-fast。

## 登录态风险

- Chrome profile 可能过期、损坏或被锁。
- 固定 profile 目录不能多实例同时使用。
- 启动时清理浏览器进程可能影响人工登录接管，需要设计清楚任务生命周期。

## 页面改版风险

JD 联盟页面可能改版，按钮文本、下拉结构、弹框 DOM 都可能变化。实现时要集中维护选择器，并在失败时输出诊断信息。

## 数据风险

- 推广链接属于业务数据，应写入指定输出文件，不写入日志过多敏感上下文。
- 不保存账号密码、cookie、token 到仓库。
- profile 目录应加入 `.gitignore` 或部署外部目录。

## 并发风险

- 同一 JD profile 必须串行。
- 批量任务需要限速，避免过快点击导致页面异常或账号风控。

# 验证方案

## 本阶段验证

本阶段只提交计划文档：

- 路径：`docs/plan/jd-union-custom-crawl-plan.md`
- 不修改业务代码。
- 不触发部署。

## 后续实现阶段本地验证

基础编译：

```bash
mvn -pl crawler-bootstrap -am test
```

单元测试：

```bash
mvn -pl crawler-bootstrap -am -DskipTests=false -Dtest=JdUnionPromotionTaskTests test
```

手动集成测试建议显式开启，避免默认访问外部页面：

```bash
mvn -pl crawler-bootstrap -am -DskipTests=false \
  -Dtest=JdUnionPromotionTaskTests \
  -Djd.union.integration=true \
  -Dapp.browser.fingerprintEnabled=true \
  -Dapp.browser.fingerprintHeadless=false \
  -Dapp.custom.jdUnion.profileDataPath=D:/app-crawler/data/chrome/jd-union test
```

人工验证流程：

1. 启动应用，确保 ChromeDriver 路径正确。
2. 调用 JD 登录检查接口。
3. 如果返回 `LOGIN_REQUIRED`，在打开的 Chrome 中人工登录 JD 联盟。
4. 重新调用生成推广链接接口。
5. 输入 `skuId=100059484008`、`adSiteName=5`。
6. 确认返回 `SUCCESS` 且 `promotionUrl` 非空。
7. 确认 output.jsonl 写出成功。

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

后续实现阶段成功标准：

1. Maven 编译通过。
2. 默认单元测试通过。
3. 浏览器基线预检不通过时，任务返回 `BROWSER_FINGERPRINT_CHECK_FAILED`，不会继续访问 JD 联盟任务页。
4. 未登录时，任务返回 `LOGIN_REQUIRED`，并允许人工登录后复用硬盘 profile。
5. 已登录且页面正常时，输入商品 ID 和广告位名称后能返回推广链接。
6. 批量输入能逐条写出结果，失败项也有状态和原因。
7. CI workflow run 结束且 `conclusion=success`。
