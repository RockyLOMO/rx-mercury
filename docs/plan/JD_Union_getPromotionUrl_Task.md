# 京东联盟 getPromotionUrl 抓取任务

## 任务定义

- 任务名：`getPromotionUrl`
- 目标：根据京东商品 ID 和推广位名称，进入京东联盟后台生成推广短链。
- 运行前置：每次抓取前先通过 `https://bot.sannysoft.com/` 指纹检测。
- 浏览器：仅使用本机 Chrome + Playwright，持久化 profile，优先模拟真实人工操作，不追求并发效率。

## 订单任务：getPromotionOrders

- 任务名：`getPromotionOrders`
- 初始页：`https://union.jd.com/entire`
- 登录页前缀：`https://union.jd.com/index?returnUrl=`
- 业务页面：`https://union.jd.com/order`
- 前置流程：复用公共 Sannysoft 检测与人工登录接管，默认等待配置仍来自 `app.custom.jdUnion`。
- 调试开关：`debugEnabled` 已提升到全局 `app.custom.debugEnabled`，测试默认建议开启，便于读取 HTML 快照排查问题。
- 任务超时：`app.custom.maxTaskMinutes` 默认 4 分钟，超时后返回 `TIMEOUT`。
- 页面遮挡处理：进入页面后如果右上角 `消息公告` 遮挡主体，先点 `收起`。
- 窗口要求：任务开始时先最大化 Chrome，避免底部翻页和表格区域被裁切。

### 入参

请求对象：`JdUnionPromotionOrdersRequest`

| 字段 | 必填 | 说明 | 示例 |
| --- | --- | --- | --- |
| `startTime` | 是 | 开始时间，格式 `yyyy-MM-dd`，要求 `startTime <= endTime` | `2026-04-15` |
| `endTime` | 是 | 结束时间，格式 `yyyy-MM-dd` | `2026-05-08` |
| `profileName` | 否 | Chrome profile 名称，默认 `common` | `common` |
| `forcePreflight` | 否 | 是否强制每次先跑 Sannysoft，默认使用配置 | `true` |
| `keepBrowserOpenOnLoginRequired` | 否 | 未登录时是否保留浏览器给人工接管 | `true` |
| `outputPath` | 否 | 结果 JSONL 写出路径 | `D:/app-crawler/data/jd-union/output.jsonl` |
| `debugEnabled` | 否 | 是否保存关键步骤 HTML 快照，未传时默认取 `app.custom.debugEnabled` | `true` |
| `debugOutputDir` | 否 | debug 输出目录 | `D:/app-crawler/data/jd-union/debug` |

最小入参：

```json
{
  "startTime": "2026-04-15",
  "endTime": "2026-05-08"
}
```

### 出参

返回对象：`JdUnionPromotionOrdersResult`

| 字段 | 说明 |
| --- | --- |
| `status` | `SUCCESS` 表示成功，其它值表示失败原因 |
| `taskType` | 固定为 `getPromotionOrders` |
| `startTime` / `endTime` | 查询时间范围 |
| `orders` | 订单数组，无订单时为空数组 |
| `loginRequired` | 是否需要人工登录 |
| `fingerprintPassed` | Sannysoft 是否通过 |
| `diagnostics` | 页码、数量、debug 目录等诊断信息 |

`orders` 每项字段：

| 字段 | 说明 |
| --- | --- |
| `productName` | 商品名称 |
| `productLink` | 商品超链接；页面 DOM 未提供时可为空 |
| `productPrice` | 商品价格 |
| `storeName` | 店铺名 |
| `orderNo` | 订单号 |
| `mainOrderNo` | 主单号 |
| `orderStatus` | 订单状态 |
| `time` | 时间原始文本 |
| `orderTime` | 下单时间 |
| `finishTime` | 完成时间 |
| `settleTime` | 结算时间 |
| `estimatedBillingAmount` | 预估计佣金额 |
| `estimatedCommission` | 预估佣金 |
| `commissionRate` | 佣金比例 |
| `shareRate` | 分成比例 |
| `actualBillingAmount` | 实际计佣金额 |
| `actualCommission` | 实际佣金 |
| `quantity` | 数量原始文本 |
| `productQuantity` | 商品数量 |
| `afterSaleQuantity` | 售后数量 |
| `returnQuantity` | 退货数量 |
| `promotionInfo` | 推广信息原始文本 |
| `promotionPosition` | 推广位 |
| `orderType` | 订单类型 |

### 页面流程

1. 进入 `https://union.jd.com/entire`，必要时等待人工登录。
2. 点击左侧菜单 `订单明细`，再点击 `推客推广订单明细`；如果页面结构变化导致菜单不可用，兜底进入 `https://union.jd.com/order`。
3. 如果页面右上角有 `消息公告` 遮挡主体，先点击 `收起`。
4. 任务开始时先把浏览器窗口最大化。
5. 点击 `时间范围` 后面的非原生日期范围组件。
6. 按入参月份调整弹框月份：左侧月份对齐 `startTime`，点击左侧日期；如果 `endTime` 与左侧同月则继续点左侧日期，否则调整右侧月份后点击右侧日期。
7. 点击 `查找订单`。
8. 抓取当前页订单表格，优先读取 `outerHTML` 并解析；如果 `下一页` 可点击则翻页继续抓取，直到下一页不可点击。
9. 如果表格区域需要额外滚动，先滚动到表格底部再继续读下一屏。
10. 如果翻页后页面数据重复，视为已到末页，停止继续翻页。

### HTTP 调用

```http
POST /custom/jd-union/getPromotionOrders
Content-Type: application/json
```

```json
{
  "startTime": "2026-04-15",
  "endTime": "2026-05-08",
  "debugEnabled": true
}
```

## 入参

请求对象：`JdUnionPromotionRequest`

| 字段 | 必填 | 说明 | 示例 |
| --- | --- | --- | --- |
| `skuId` | 是 | 商品 ID，只允许数字 | `100341910908` |
| `adSiteName` | 是 | 推广位名称，只允许数字 | `5` |
| `profileName` | 否 | Chrome profile 名称，默认 `common` | `common` |
| `forcePreflight` | 否 | 是否强制每次先跑 Sannysoft，默认使用配置 | `true` |
| `keepBrowserOpenOnLoginRequired` | 否 | 未登录时是否保留浏览器给人工接管 | `true` |
| `outputPath` | 否 | 结果 JSONL 写出路径 | `D:/app-crawler/data/jd-union/output.jsonl` |

最小入参：

```json
{
  "skuId": "100341910908",
  "adSiteName": "5"
}
```

## 出参

返回对象：`JdUnionPromotionResult`

关键字段：

| 字段 | 说明 |
| --- | --- |
| `status` | `SUCCESS` 表示成功，其它值表示失败原因 |
| `taskType` | 固定为 `getPromotionUrl` |
| `skuId` | 商品 ID |
| `adSiteName` | 推广位名称 |
| `productInfo` | 商品信息 DTO |
| `promotionUrl` | 成功时返回推广链接 |
| `loginRequired` | 是否需要人工登录 |
| `fingerprintPassed` | Sannysoft 是否通过 |
| `message` | 失败说明 |
| `diagnostics` | 诊断信息 |

成功示例：

```json
{
  "status": "SUCCESS",
  "taskType": "getPromotionUrl",
  "skuId": "100341910908",
  "adSiteName": "5",
  "productInfo": {
    "imageUrl": "https://...",
    "productName": "小白熊摇奶器保温二合一温奶...",
    "productLink": "https://item.jd.com/...",
    "commissionRate": "4.8%",
    "finalPrice": "480.95",
    "storeName": "小白熊京东自营旗舰店"
  },
  "mediaType": "导购媒体推广",
  "mediaName": "微信",
  "profileName": "common",
  "promotionUrl": "https://u.jd.com/f6FcZZw",
  "loginRequired": false,
  "fingerprintPassed": true,
  "message": ""
}
```

## 文字流程

1. 打开 `https://bot.sannysoft.com/`。
2. 检查浏览器指纹结果：
   - `navigator.webdriver` 必须为空或缺失。
   - `window.chrome` 必须存在。
   - `plugins`、`languages` 等基础项必须正常。
   - 严格模式下页面报告不能出现明显失败项。
3. Sannysoft 通过后，进入京东联盟初始页：`https://union.jd.com/overview`。
4. 如果跳转到登录页 `https://union.jd.com/index?returnUrl=` 或 `passport.jd.com`：
   - 程序暂停等待人工登录。
   - 默认最多等待 `180` 秒，可通过配置调整。
   - 人工登录完成后，页面应回到初始页或京东联盟已登录页面。
5. 进入商品推广页：
   - 优先从左侧菜单点击 `我要推广`。
   - 再点击 `商品推广`。
   - 如果菜单未找到，兜底进入 `https://union.jd.com/proManager/index?pageNo=1`。
6. 在商品推广页搜索商品：
   - 在商品搜索输入框输入 `skuId`。
   - 点击 `搜索全部商品`。
   - 等待结果加载。
7. 搜索后页面下滚，避免目标商品的 `我要推广` 按钮被遮挡。
8. 从唯一商品卡片提取商品信息并写入 `productInfo`：
   - 商品图片 URL
   - 商品名
   - 商品链接
   - 佣金比例
   - 到手价
   - 店铺名
9. 判断搜索结果：
   - `所有结果 共0件商品` 或无结果文案：返回 `NOT_FOUND`。
   - 搜索结果数量大于 1：返回 `MULTIPLE_MATCHED`。
   - 唯一结果：继续。
10. 点击目标商品区域内的 `我要推广`。
11. 如果出现权益确认，点击 `已获取权益，继续推广` 或 `继续推广`。
12. 在 `生成推广链接` 弹窗内：
    - 推广类型选择 `导购媒体推广`。
    - 点击 `所属导购媒体` 非原生下拉框。
    - 在下拉选项中点击 `微信`。
    - 投放推广位选择 `选择推广位`。
    - 点击 `推广位名称` 非原生下拉框。
    - 在下拉选项中点击入参 `adSiteName`，例如 `5`。
13. 点击 `获取推广链接`。
14. 等待推广链接生成。
15. 点击 `复制`。
16. 从弹窗输入框、文本区域或页面文本读取 `https://...` 链接，优先取 `优惠券链接`，再取普通 `推广链接`，写入 `promotionUrl`。

### Cookie 记录

- `cookies.html` 现在作为对比页使用，会同时展示当前 `HttpServletRequest` 的 `Cookie` 头和 `HttpClientCookieJar` 里同一 URL 对应的 cookie。
- `getPromotionUrl` 成功结束时，会把当前 Playwright 页面上下文中的 cookie 一次性保存进 `HttpClientCookieJar`，包括 `HttpOnly` cookie。
- 本地持久化改为 `HttpClientCookieJar + H2CookieStorage`，不再依赖 Redis cookie 容器。

## Debug 模式

- `debugEnabled=true` 时，任务会在 `debugOutputDir/profileName/skuId-时间戳/` 下保存每个关键步骤的 `html` 快照。
- 全局默认开关来自 `app.custom.debugEnabled`，请求里的 `debugEnabled` 只作为显式覆盖。
- 每个快照文件名包含步骤序号，便于按执行顺序排查。
- 默认关闭，不影响正常抓取流程。

## 当前项目 Server 交互

当前项目作为 server 时，由 Spring Boot 启动 `crawler-bootstrap`。配置项：

```yaml
app:
  custom:
    remotingEnabled: true
    remotingListenPort: 1221
```

启动后，`CustomCrawlRemotingService` 会在初始化时执行：

```java
Remoting.register(this, appConfig.getCustom().getRemotingListenPort(), false);
```

也就是说，当前项目会把统一的 `CustomCrawlRemotingContract` 暴露到 `1221` 端口。

## Remoting Client 调用

客户端复用独立的 JD Union API jar 和 RPC 依赖：

`crawler-api` 模块提供：

- `org.rx.crawler.task.common.CustomCrawlRemotingContract`
- `org.rx.crawler.task.jd.JdUnionPromotionRequest`
- `org.rx.crawler.task.jd.JdUnionPromotionResult`
- `org.rx.crawler.task.jd.JdUnionPromotionOrdersRequest`
- `org.rx.crawler.task.jd.JdUnionPromotionOrdersResult`
- `org.rx.crawler.task.jd.JdUnionBatchRequest`

外部项目可直接依赖：

```xml
<dependency>
    <groupId>org.rx</groupId>
    <artifactId>crawler-api</artifactId>
    <version>1.0</version>
</dependency>
```

```java
import org.rx.crawler.task.common.CustomCrawlRemotingContract;
import org.rx.crawler.task.jd.JdUnionPromotionRequest;
import org.rx.crawler.task.jd.JdUnionPromotionResult;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RpcClientConfig;

import static org.rx.core.Extends.tryClose;

public class JdUnionGetPromotionUrlClient {
    public static void main(String[] args) {
        CustomCrawlRemotingContract client = Remoting.createFacade(
                CustomCrawlRemotingContract.class,
                RpcClientConfig.statefulMode("127.0.0.1:1221", 0));
        try {
            JdUnionPromotionRequest request = new JdUnionPromotionRequest();
            request.setSkuId("100341910908");
            request.setAdSiteName("5");

            JdUnionPromotionResult result = client.getPromotionUrl(request);
            System.out.println(result.getStatus());
            System.out.println(result.getPromotionUrl());
            System.out.println(result.getMessage());
        } finally {
            tryClose(client);
        }
    }
}
```

统一推荐调用：

```java
JdUnionPromotionResult result = client.getPromotionUrl(request);
```

## HTTP 调用入口

除 remoting 外，当前项目也暴露 HTTP 入口：

```http
POST /custom/jd-union/getPromotionUrl
Content-Type: application/json
```

请求：

```json
{
  "skuId": "100341910908",
  "adSiteName": "5"
}
```

响应外层为统一 `Result<T>`：

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "",
  "data": {
    "status": "SUCCESS",
    "taskType": "getPromotionUrl",
    "skuId": "100341910908",
    "adSiteName": "5",
    "productInfo": {
      "imageUrl": "https://...",
      "productName": "...",
      "productLink": "https://...",
      "commissionRate": "4.8%",
      "finalPrice": "480.95",
      "storeName": "..."
    },
    "promotionUrl": "https://u.jd.com/f6FcZZw",
    "fingerprintPassed": true,
    "loginRequired": false
  }
}
```

## 下个抓取任务描述模板

后续新任务可以按下面格式发需求，便于直接实现。

```md
# 抓取任务

## 任务名
例如：getPromotionUrl

## 目标网站
- 初始页：
- 登录页前缀：
- 业务页面：

## 入参
| 字段 | 类型 | 必填 | 说明 | 示例 |
| --- | --- | --- | --- | --- |

## 出参
| 字段 | 类型 | 说明 |
| --- | --- | --- |

## 前置通用流程
1. 是否需要先过 Sannysoft：
2. 是否需要人工登录接管：
3. 初始页最大等待时间：
4. 登录页最大等待时间：

## 页面操作流程
1. 打开什么页面。
2. 点击什么菜单或按钮。
3. 输入什么字段。
4. 遇到弹窗/非原生下拉/遮挡/分页时如何处理。
5. 最终从哪里读取结果。

## 固定选项
- 选项 A：
- 选项 B：

## 异常判断
- 无结果：
- 多结果：
- 页面结构变化：
- 登录失效：

## 验证用例
- 测试账号状态：
- 示例入参：
- 期望结果：
```

## 本次验证记录

验证命令使用 JDK 21、本机 Chrome profile：

```powershell
mvn -pl crawler-bootstrap -am "-Dmaven.test.skip=false" "-DskipTests=false" "-Dtest=JdUnionPromotionTaskTests#jdUnionAuthorizedIntegration" "-Djd.union.integration=true" "-Djd.union.skuId=100341910908" "-Djd.union.adSiteName=5" test
```

验证结果：

```text
Sannysoft: passed
JD skuId: 100341910908
adSiteName: 5
status: SUCCESS
promotionUrl: https://u.jd.com/f6FcZZw
```

