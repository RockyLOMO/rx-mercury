# rx-mercury 项目 AGENTS 指南

## 项目定位

- 本项目当前以 `JDK21 + Playwright` 为准，`Selenium` 已移除。
- 抓取目标优先考虑对抗反爬，流程以真实用户操作为主，不追求高效并发。
- 仅先支持 `Chrome`，其他浏览器暂不考虑。

## 通用抓取原则

- 所有抓取任务执行前，优先通过 `https://bot.sannysoft.com/` 检测。
- 如果进入登录页，交给人工接管，程序只负责等待和恢复后续流程。
- 初始页、登录页等待时长、人工接管等待时长都必须可配置。
- 所有任务都应复用公共前置流程，不要在具体任务里重复造轮子。

## JD 联盟任务约定

- 任务名统一使用 `getPromotionUrl`。
- 不保留旧的大小写兼容名。
- `getPromotionUrl` 返回结果中必须包含 `productInfo`。
- `productInfo` 字段固定为：
  - `imageUrl`
  - `productName`
  - `productLink`
  - `commissionRate`
  - `finalPrice`
  - `storeName`
- 生成推广链接时，若同时存在优惠券链接和普通链接，优先取优惠券链接。

## 本地验证约定

- 本地验证时，默认要把 `debugEnabled=true` 打开。
- `debugEnabled=true` 时，任务应保存关键步骤的 HTML 快照，方便排查页面变化。
- 若未显式指定 `debugOutputDir`，优先写到项目约定的本地调试目录。

## 代码与测试

- 修改任务流程后，必须补充对应单测或验证记录。
- 关键抓取流程变更后，至少跑一次本地集成验证。
- 文档同步更新到 `docs/plan/JD_Union_getPromotionUrl_Task.md`。

