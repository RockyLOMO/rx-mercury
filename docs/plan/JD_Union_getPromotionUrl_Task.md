# 京东联盟 getPromotionUrl 抓取任务

## 任务定义

- 任务名：`getPromotionUrl`
- 目标：根据京东商品 ID 和推广位名称，进入京东联盟后台生成推广短链。
- 运行前置：每次抓取前先通过 `https://bot.sannysoft.com/` 指纹检测。
- 浏览器：仅使用本机 Chrome + Playwright，持久化 profile，优先模拟真实人工操作，不追求并发效率。

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
8. 判断搜索结果：
   - `所有结果 共0件商品` 或无结果文案：返回 `NOT_FOUND`。
   - 搜索结果数量大于 1：返回 `MULTIPLE_MATCHED`。
   - 唯一结果：继续。
9. 点击目标商品区域内的 `我要推广`。
10. 如果出现权益确认，点击 `已获取权益，继续推广` 或 `继续推广`。
11. 在 `生成推广链接` 弹窗内：
    - 推广类型选择 `导购媒体推广`。
    - 点击 `所属导购媒体` 非原生下拉框。
    - 在下拉选项中点击 `微信`。
    - 投放推广位选择 `选择推广位`。
    - 点击 `推广位名称` 非原生下拉框。
    - 在下拉选项中点击入参 `adSiteName`，例如 `5`。
12. 点击 `获取推广链接`。
13. 等待推广链接生成。
14. 点击 `复制`。
15. 从弹窗输入框、文本区域或页面文本读取 `https://...` 链接，优先取 `优惠券链接`，再取普通 `推广链接`，写入 `promotionUrl`。

## 当前项目 Server 交互

当前项目作为 server 时，由 Spring Boot 启动 `crawler-bootstrap`。配置项：

```yaml
app:
  custom:
    remotingEnabled: true
    remotingListenPort: 1211
```

启动后，`JdUnionPromotionTask` 会在初始化时执行：

```java
Remoting.register(this, appConfig.getCustom().getRemotingListenPort(), false);
```

也就是说，当前项目会把 `JdUnionCrawlContract` 暴露到 `1211` 端口。

## Remoting Client 调用

客户端复用当前工程的 RPC 依赖和契约类：

```java
import org.rx.crawler.task.jd.JdUnionCrawlContract;
import org.rx.crawler.task.jd.JdUnionPromotionRequest;
import org.rx.crawler.task.jd.JdUnionPromotionResult;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RpcClientConfig;

import static org.rx.core.Extends.tryClose;

public class JdUnionGetPromotionUrlClient {
    public static void main(String[] args) {
        JdUnionCrawlContract client = Remoting.createFacade(
                JdUnionCrawlContract.class,
                RpcClientConfig.statefulMode("127.0.0.1:1211", 0));
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

兼容入口仍可调用：

```java
JdUnionPromotionResult result = client.promotion(request);
```

但新任务统一推荐调用：

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

