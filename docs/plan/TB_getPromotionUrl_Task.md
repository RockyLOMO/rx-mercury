# 淘宝联盟 getTbPromotionUrl 抓取任务

## 任务范围

- 任务名：`getTbPromotionUrl`
- 浏览器：本机 Chrome + Playwright，复用持久化 profile。
- 前置流程：复用公共 Sannysoft 检测、人工登录接管和等待恢复流程。
- 风控滑块：任务关键步骤会优先识别滑动验证并保存 debug 快照；检测后先调用 `onSliderVerifyDetected(...)` 空钩子，默认不处理并等待人工处理完成后继续当前流程。
- 页面操作：优先使用 `Browser` 封装的原生点击、输入、鼠标移动能力；JS 只用于定位、读取页面状态或兜底。

## 目标网站

- 初始页：`https://pub.alimama.com/portal/v2/home/plus/index.htm`
- 登录页前缀：`https://login.taobao.com/havanaone/login/login.htm`
- 业务页面：`https://pub.alimama.com/portal/v2/pages/promo/goods/index.htm`

## 入参

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `productInfo` | 是 | 商品 id、商品名或商品链接 |
| `adSiteName` | 是 | 推广位数字，例如 `5` |
| `mediaName` | 否 | 媒体名称；不传时使用页面当前默认值 |
| `profileName` | 否 | Chrome profile 名称，默认 `common` |
| `forcePreflight` | 否 | 是否强制每次先跑 Sannysoft |
| `debugEnabled` | 否 | 是否保存关键 HTML 快照；发布默认关闭，测试/本地验证显式传 `true` |
| `debugOutputDir` | 否 | 调试快照目录 |

## 出参

| 字段 | 说明 |
| --- | --- |
| `taskType` | 固定为 `getTbPromotionUrl` |
| `productInfo` | 复用 `JdUnionProductInfoDto`，包含商品图、名称、链接、佣金率、到手价、店铺名 |
| `promotionUrl` | 推广链接，优先从输入框/文本区读取，点击一键复制后再兜底读取页面内容 |
| `fingerprintPassed` | Sannysoft 是否通过 |
| `loginRequired` | 是否需要人工登录 |
| `diagnostics` | 调试诊断信息 |

## 页面流程

1. 进入阿里妈妈初始页，执行 Sannysoft 与登录接管。
2. 打开选品推广业务页。
3. 在 placeholder 为“请输入你要搜索的商品/类目/商品链接”的输入框输入 `productInfo`。
4. 点击“智能搜索”。
5. 若搜索点击未进入搜索态，使用带 `fn=search&q=` 的业务 URL 兜底进入搜索结果页。
6. 下拉页面，若没有商品则返回 `NOT_FOUND`。
7. 取第一条商品卡片，先用原生鼠标移动 hover，再抓取商品信息：
   - `imageUrl`
   - `productName` + `productLink`：优先取商品标题外层的 `a[href][data-spm-click*=d_good_select_list_title_click]`，也就是标题区域的详情页超链接。
   - `finalPrice`
   - `commissionRate`
   - `storeName`
8. 点击第一条商品的“立即推广”。
9. 在素材弹窗中点击“推广位”选择框。
10. 在选择推广位弹窗中选择“他方平台”到“社交平台”。
11. 若请求传入 `mediaName`，再选择对应媒体名称。
12. 打开“推广位名称”，选择首个数字等于 `adSiteName` 的选项。
13. 点击“确认”。
14. 回到素材弹窗，点击“一键复制”，读取 `promotionUrl`。

## 本地验证

默认建议开启：

```json
{
  "productInfo": "西麦纯燕麦片3kg高蛋白质0添加蔗糖即食谷物速食懒人代餐冲饮早餐",
  "adSiteName": "5",
  "debugEnabled": true
}
```

本地集成验证命令：

```powershell
mvn -pl crawler-bootstrap -am "-Dmaven.test.skip=false" "-DskipTests=false" "-Dtest=TbPromotionUrlTaskTests#tbPromotionUrlIntegrationShouldSaveReadableDebugSnapshot" "-Dtb.promotion.url.integration=true" test
```
