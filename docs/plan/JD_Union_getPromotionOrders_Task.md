# 京东联盟 getPromotionOrders 抓取任务

## 任务定义

- 任务名：`getPromotionOrders`
- 目标：进入京东联盟订单明细页，按时间范围抓取推客推广订单列表。
- 运行前置：先过 `https://bot.sannysoft.com/` 指纹检测；需要人工登录时交给人工接管。
- 浏览器：仅使用本机 Chrome + Playwright，优先复用已有登录态 profile；`app.custom.chrome.closeBrowserAfterTask` 默认 `false`，任务完成后保留并复用同 profile 的 Chrome，设为 `true` 可恢复普通任务完成即关闭。

## 入参

请求对象：`JdUnionPromotionOrdersRequest`

| 字段 | 必填 | 说明 | 示例 |
| --- | --- | --- | --- |
| `startTime` | 是 | 开始日期，格式 `yyyy-MM-dd` | `2026-04-15` |
| `endTime` | 是 | 结束日期，格式 `yyyy-MM-dd` | `2026-05-08` |
| `profileName` | 否 | Chrome profile 名称，默认 `common` | `common` |
| `forcePreflight` | 否 | 是否强制每次先跑 Sannysoft | `true` |
| `keepBrowserOpenOnLoginRequired` | 否 | 未登录时是否保留浏览器给人工接管 | `true` |
| `debugEnabled` | 否 | 是否保存关键步骤 HTML 快照，未传时默认取全局配置；发布默认关闭，测试/本地验证显式传 `true` | `true` |
| `debugOutputDir` | 否 | debug 输出目录 | `D:/app-crawler/data/jd-union/debug` |

校验规则：

- `startTime <= endTime`
- 日期范围为空不允许执行

## 出参

返回对象：`JdUnionPromotionOrdersResult`

| 字段 | 说明 |
| --- | --- |
| `status` | `SUCCESS`、`LOGIN_REQUIRED`、`TIMEOUT`、`FAILED` 等 |
| `taskType` | 固定为 `getPromotionOrders` |
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

## 页面流程

1. 打开 `https://union.jd.com/entire`。
2. 如果跳到登录页，交给人工登录接管，登录完成后继续。
3. 点击左侧菜单 `订单明细 -> 推客推广订单明细`。
4. 页面右上方如果有 `消息公告` 遮挡，先点 `收起`。
5. 任务开始时先把浏览器窗口最大化，避免底部翻页和表格区域被遮住。
6. 找到右侧 `时间范围` 后面的非原生日期范围组件并打开。
7. 按入参选择日期：
   - 左侧月份对齐 `startTime` 月份。
   - 点击左侧对应日期作为开始日期。
   - 如果 `endTime` 与左侧同月，直接在左侧选择结束日期。
   - 如果不在同月，切换右侧月份到 `endTime` 月份，再在右侧选择结束日期。
8. 点击 `查找订单`。
9. 如果没有订单，返回空数组。

## 表格抓取逻辑

1. 订单表格优先按 `table.el-table__body` 读取。
2. 当前页面结构里，主体数据通常直接在 `tr/td` 中，不一定有 `tbody`。
3. 抓取时按页面红框内容从左到右、从上到下解析：
   - 第 1 列商品信息：商品名称、商品超链接、商品价格、店铺名。
   - 第 2 列订单号：订单号、主单号。
   - 第 3 列订单状态：订单状态。
   - 第 4 列时间：下单时间、完成时间、结算时间。
   - 第 5 列：预估计佣金额。
   - 第 6 列：预估佣金。
   - 第 7 列：佣金比例。
   - 第 8 列：分成比例。
   - 第 9 列：实际计佣金额。
   - 第 10 列：实际佣金。
   - 第 11 列数量：商品数量、售后数量、退货数量。
   - 第 12 列推广信息：推广位。
   - 第 13 列：订单类型。
4. 如果直接读 DOM 不稳定，优先保存表格 `outerHTML`，再用 HTML 解析补抓。
5. 同一页内如果表格需要滚动加载，先滚到表格顶部，再逐步向下滚动读取可见行。
6. 行数据按订单号/状态/时间等组合去重后汇总。

## 翻页逻辑

1. 每页抓完后把页面滚动到底部。
2. 检查 `下一页` 是否可点击。
3. 如果可点击，点击后继续抓下一页。
4. 直到 `下一页` 不可点击为止。

## 调试与超时

- `debugEnabled=true` 时，任务会在每个关键步骤保存 HTML 快照。
- 全局默认值来自 `app.custom.debugEnabled`，发布默认关闭；测试类里显式开启。
- 任务最大执行时间来自 `app.custom.maxTaskMinutes`，当前默认 4 分钟。
- 超时后返回 `TIMEOUT`，并保留最后一张快照用于排查。

## 验证结论

- 已验证页面结构中订单主体表格可从 `el-table__body` 读取到数据行。
- 已验证分页按钮位于页面底部，需要先滚动到位再点击。
- 已验证公告遮挡会影响操作，进入页面后应先收起公告面板。
