# 淘宝联盟 getTbPromotionOrders 抓取任务

## 任务定义

- 任务名：`getTbPromotionOrders`
- 目标：进入阿里妈妈订单明细页，按付款时间范围抓取推广订单列表。
- 运行前置：先过 `https://bot.sannysoft.com/` 指纹检测；需要人工登录时交给人工接管。
- 浏览器：仅使用本机 Chrome + Playwright。
- Chrome profile：默认使用 `common`，用于复用和记录人工登录态。

## 入参

请求对象：`TbPromotionOrdersRequest`

| 字段 | 必填 | 说明 | 示例 |
| --- | --- | --- | --- |
| `startTime` | 是 | 开始日期，格式 `yyyy-MM-dd` | `2026-04-15` |
| `endTime` | 是 | 结束日期，格式 `yyyy-MM-dd` | `2026-05-08` |
| `profileName` | 否 | Chrome profile 名称，默认 `common` | `common` |
| `forcePreflight` | 否 | 是否强制每次先跑 Sannysoft | `true` |
| `keepBrowserOpenOnLoginRequired` | 否 | 未登录时是否保留浏览器给人工接管 | `true` |
| `debugEnabled` | 否 | 是否保存关键步骤 HTML 快照，未传时默认取全局配置 | `true` |
| `debugOutputDir` | 否 | debug 输出目录 | `D:/app-crawler/data/tb/debug` |

校验规则：

- `startTime <= endTime`
- 日期范围为空不允许执行

## 出参

返回对象：`TbPromotionOrdersResult`

| 字段 | 说明 |
| --- | --- |
| `status` | `SUCCESS`、`LOGIN_REQUIRED`、`TIMEOUT`、`FAILED` 等 |
| `taskType` | 固定为 `getTbPromotionOrders` |
| `startTime` / `endTime` | 查询时间范围 |
| `orders` | 订单数组，无订单时为空数组 |
| `loginRequired` | 是否需要人工登录 |
| `fingerprintPassed` | Sannysoft 是否通过 |
| `diagnostics` | 页码、条数、debug 目录等诊断信息 |

`orders` 每项字段：

| 字段 | 说明 |
| --- | --- |
| `productName` | 商品名称 |
| `productLink` | 商品超链接；页面 DOM 未提供时为空 |
| `productPrice` | 商品价格；页面未提供时为空 |
| `storeName` | 店铺名 |
| `orderNo` | 订单号 |
| `mainOrderNo` | 主单号 |
| `orderStatus` | 订单状态 |
| `orderTime` | 下单时间 |
| `estimatedBillingAmount` | 预估计佣金额 |
| `estimatedCommission` | 预估佣金，第 4 列两行右侧金额相加 |
| `commissionRate` | 佣金比例 |
| `actualBillingAmount` | 实际计佣金额；实际佣金不为空时取预估计佣金额 |
| `actualCommission` | 实际佣金，第 5 列两行右侧金额相加；页面为 `--` 时为空 |

## 页面流程

1. 打开 `https://pub.alimama.com/portal/v2/home/plus/index.htm`，必要时等待人工登录。
2. 如果登录后跳转到 `https://pub.alimama.com/?forward=...` 中转页，先点击 `快速进入`。
3. 进入业务页面 `https://pub.alimama.com/portal/v2/effect/order/overviewOrder/page/index.htm`。
4. 点击 `付款时间` 右侧的 `时间范围` 非原生组件。
5. 弹出 `选择时间：` 面板后，点击第二行任意日期输入框打开日期选择器。
6. 按入参选择日期：
   - 左侧月份对齐 `startTime` 月份。
   - 点击左侧对应日期作为开始日期。
   - 如果 `endTime` 与左侧同月，直接在左侧选择结束日期。
   - 如果不在同月，切换右侧月份到 `endTime` 月份，再在右侧选择结束日期。
7. 点击 `搜索订单编号` 输入框右侧的 `查找订单`。
8. 如果没有订单，返回空数组。

## 表格抓取逻辑

1. 订单列表优先直接读取 DOM 行结构。
2. DOM 不稳定时，保存并解析列表 `outerHTML`。
3. 每行按页面从左到右解析：
   - 第 1 列：商品名称、商品链接、店铺名、主单号、订单号、预估计佣金额、下单时间。
   - 第 2 列：订单状态。
   - 第 3 列：佣金比例。
   - 第 4 列：两行右侧金额相加为预估佣金。
   - 第 5 列：两行右侧金额相加为实际佣金；如果为 `--`，实际佣金为空；实际佣金不为空时，实际计佣金额取预估计佣金额。
4. 行数据按订单号、主单号、下单时间、佣金等组合去重后汇总。

## 翻页逻辑

1. 每页抓完后滚动到底部。
2. 检查 `下一页` 是否可点击。
3. 如果可点击，点击后继续抓下一页。
4. 直到 `下一页` 不可点击为止。

## 本地验证

- 本地验证建议开启 `debugEnabled=true`。
- 示例入参：近一个月的 `startTime` 和 `endTime`。
- 期望结果：有多页订单数据返回。

## 调用入口

HTTP 入口：

```text
POST /custom/tb/getPromotionOrders
```

Remoting RPC 统一使用：

```java
org.rx.crawler.task.common.CustomCrawlRemotingContract#getTbPromotionOrders
```

## 滑块验证处理

淘宝联盟页面在高频访问或账号风控时，会弹出"亲，请拖动下方滑块完成验证"的验证页（阿里妈妈防爬机制）。

### 触发节点

以下三个节点后会自动检测并处理滑块验证：

1. 导航进入订单页后（`02-order-slider`）
2. 点击"查找订单"搜索后（`04-search-slider`）
3. 每次翻页后（`05-page-NNN-slider`）

### 检测逻辑

- 读取页面 `body.innerText`，若包含以下任意文字则判定为验证页：
  - `请拖动下方滑块完成验证`
  - `拖动滑块`
  - `拖到最右边`
  - `按住滑块`

### 模拟滑动策略

- 通过 JS 遍历 DOM 定位滑块把手元素（class 含 `slider/handle/btn/drag`），获取其屏幕坐标。
- 轨道宽度通过 class 含 `track/rail/groove` 的元素读取；未找到时以把手宽度兜底。
- 拖拽距离 = `轨道宽 - 把手宽 - 2px`，最小 100px。
- 通过 `dispatchEvent` 依次发送：`mousedown` → 30 步 `mousemove`（先快后慢，加随机抖动模拟真人）→ `mouseup`。
- Java 侧同步分 15 步补发 `mousemove`，总耗时约 1.5s，增强时序真实性。
- 每次验证最多重试 3 次，失败后等待后继续重试；3 次均失败时记录 `sliderVerifyFailed` 到 `diagnostics`，不中断主流程。

### 诊断字段

| 字段 | 说明 |
| --- | --- |
| `sliderVerifyAt` | 最近一次触发验证的步骤标签 |
| `sliderVerifyPassed` | 验证成功时写入 `true` |
| `sliderVerifyFailed` | 3 次重试均失败时写入触发步骤名 |
