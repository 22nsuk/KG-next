---
status: accepted
---

# 日本、韩国规则预设保留“未提净死子时允许停一手”

`KataGoRules` 的 `japanese` 和 `korean` 预设设置 `friendlyPassOk=true`。KataGo 源码里 `parseRulesHelper` 对这两个名字给的是 `false`，但 KataGo 的 `docs/GTP_Extensions.md` 写的是 `true`，两处本身就不一致。本项目沿用文档和现有值。理由：这个字段只在数子规则下影响行棋、终局和计分，点目规则下 KataGo 只把它并入局面哈希，改成 `false` 不会改变日本、韩国规则的实际行为；而用户已经保存的启动默认规则、引擎对局规则里的日本规则都带着 `true`，一改就会对不上预设。

本项目总是用完整 JSON 下发规则（`kata-set-rules {...}`），KataGo 原样保存这个字段，所以回读和预设一致，不会出现 `TARGET_MISMATCH`。

## Considered Options

- 改成 `false`，与 KataGo 源码一致：拒绝。没有行为差异，却要迁移已保存配置，否则这些规则会从“日本规则”变成“其他规则”。

## Consequences

- 日本、韩国预设按钮继续填入 `true`。规则编辑器在选“点目”时只禁用这一项，不改写原值，避免打开并保存古代点目规则或自定义规则时丢失字段。切回数子后可以继续编辑。
- 引擎配置文件里写 `rules = japanese` 启动时，KataGo 回读的是 `false`，和预设不完全相等；规则名标签会显示为“其他规则”，导入 `RU[Japanese]` 棋谱时会多下发一次规则再确认。这不影响结果。
- 其他预设（包括新增的 `goe`、`ancient-territory`）的字段与 KataGo 源码逐字段一致。
