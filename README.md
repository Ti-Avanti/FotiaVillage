# FotiaVillage

![FotiaVillage banner](assets/fotiavillage-banner.svg)

FotiaVillage 是一个面向 Paper 服务端的村民管理插件，用于集中控制村民数量、生成来源、寿命、交易成本、交易冷却、周期交易上限、交易统计和排行榜。插件自带中文与英文语言文件，并可选接入 PlaceholderAPI。

## 功能

- 区域村民数量限制：按区块半径统计村民数量，达到上限后阻止新的村民生成。
- 村民生成控制：可分别控制自然生成、僵尸村民治愈、村民刷怪蛋和村民繁殖。
- 村民寿命系统：为村民写入寿命数据，显示剩余时间，到期后自动移除并按配置通知玩家。
- 寿命道具：可在 `lifespan-items.yml` 中配置不同道具，为不同职业、类型或名称的村民增加不同天数寿命。
- 交易控制：支持完全禁用交易、经验消耗、成本递增、交易冷却、周期交易次数限制和额外绿宝石消耗。
- 权限组差异化：内置 `admin`、`svip`、`vip`、`default` 交易权限组，可分别调整经验倍率、冷却倍率和周期交易上限加成。
- 交易统计：使用 SQLite 保存玩家交易次数、消耗经验、物品统计、冷却、交易次数和成本倍率记录。
- GUI 与文本回退：玩家可通过 GUI 查看统计和排行榜；控制台或关闭 GUI 时会使用文本输出。
- PlaceholderAPI：提供玩家交易数据、排行数据、权限组和每日交易额度变量。
- 自动更新检测：启动和重载时可异步检查 GitHub Release 新版本，并通知拥有管理权限的在线玩家。

## 环境

| 项目 | 要求 |
| --- | --- |
| 服务端 | Paper `1.21.x` |
| Java | `21` |
| 构建工具 | Maven |
| 可选依赖 | PlaceholderAPI |
| 数据库 | SQLite，数据文件为 `plugins/FotiaVillage/data.db` |

## 安装

1. 从 [Releases](https://github.com/Ti-Avanti/FotiaVillage/releases/latest) 下载 `FotiaVillage-版本号.jar`。
2. 将 jar 放入服务端 `plugins` 目录。
3. 重启服务端。
4. 首次启动会自动生成 `plugins/FotiaVillage/config.yml` 与 `plugins/FotiaVillage/languages/`。
5. 如需 PlaceholderAPI 变量，将 PlaceholderAPI 安装到服务端后保持 `placeholder.enabled: true`。

## 配置入口

主配置文件为 `plugins/FotiaVillage/config.yml`。

| 配置段 | 作用 |
| --- | --- |
| `language` | 设置语言，支持 `zh_CN` 和 `en_US`。 |
| `gui.enabled` | 控制统计和排行榜是否使用 GUI。 |
| `placeholder.enabled` | 控制是否注册 PlaceholderAPI 变量。 |
| `update-checker.enabled` | 控制启动和重载时是否检查 GitHub Release。 |
| `performance` | 自动性能报告、低 TPS 警告和过期数据清理间隔。 |
| `villager-limit` | 区域村民数量限制，包含检测区块半径和最大村民数量。 |
| `spawn-control` | 控制自然生成、治愈、刷怪蛋和繁殖。 |
| `villager-lifespan` | 控制寿命天数、到期通知、自动补全寿命和检查间隔。 |
| `lifespan-items.yml` | 配置寿命道具、可用目标村民职业 ID、使用消耗和物品匹配规则。 |
| `trade-control.disable-trading` | 是否完全禁用村民交易。 |
| `trade-control.exp-cost` | 交易经验消耗，支持等级或经验点模式。 |
| `trade-control.cost-scaling` | 同一玩家对同一物品的交易成本递增。 |
| `trade-control.cooldown` | 默认、按职业、按物品的交易冷却。 |
| `trade-control.limit` | 全局、按职业、按物品的周期交易次数限制。 |
| `trade-control.permission-groups` | 按权限匹配交易倍率和额度加成。 |
| `trade-control.economy-balance` | 高价值物品额外绿宝石消耗。 |
| `trade-control.statistics` | 交易统计、详细交易日志和排行榜数量。 |
| `commands.kill-enabled` | 控制 `/fv kill` 是否可用。 |

配置文件中的注释已包含各项默认值说明。新增或修改配置后执行 `/fv reload` 即可重载配置、语言、PlaceholderAPI 注册状态和更新检测。

## 命令

主命令：`/fv`，别名：`/fotiavillage`。

| 命令 | 权限 | 说明 |
| --- | --- | --- |
| `/fv reload` | `fotiavillage.admin` | 重载配置和语言。 |
| `/fv stats` | `fotiavillage.stats` | 查看自己的交易统计。 |
| `/fv stats <玩家>` | `fotiavillage.stats` + `fotiavillage.stats.others` | 查看其他玩家的交易统计。 |
| `/fv top` | `fotiavillage.top` | 查看交易排行榜。 |
| `/fv admin reset <玩家>` | `fotiavillage.admin` | 重置指定玩家交易数据。 |
| `/fv admin clear` | `fotiavillage.admin` | 发起清空全部交易数据确认。 |
| `/fv admin clear confirm` | `fotiavillage.admin` | 在 10 秒内确认后清空全部交易数据。 |
| `/fv admin info` | `fotiavillage.admin` | 查看插件版本和数据库连接状态。 |
| `/fv perf overview` | `fotiavillage.admin` | 查看 TPS、内存和运行时间。 |
| `/fv perf memory` | `fotiavillage.admin` | 同 `overview`。 |
| `/fv perf database` | `fotiavillage.admin` | 查看数据库状态和文件大小。 |
| `/fv perf tracker` | `fotiavillage.admin` | 查看被追踪区块和村民数量。 |
| `/fv perf cleanup` | `fotiavillage.admin` | 清理过期数据。 |
| `/fv lifespan check` | `fotiavillage.admin` | 扫描已加载村民的寿命状态。 |
| `/fv lifespan add [天数]` | `fotiavillage.admin` | 为未设置寿命的已加载村民补全寿命。 |
| `/fv lifespan addtarget <天数>` | `fotiavillage.admin` | 给正在看着的指定村民增加寿命。 |
| `/fv lifespan list` | `fotiavillage.admin` | 列出最多 10 个未设置寿命的已加载村民。 |
| `/fv item give <玩家> <道具ID> [数量]` | `fotiavillage.item.give` | 按 `lifespan-items.yml` 配置给予寿命道具。 |
| `/fv kill` | `fotiavillage.kill` | 移除所有已加载村民；还需要 `commands.kill-enabled: true`。 |

## 权限

| 权限 | 默认 | 说明 |
| --- | --- | --- |
| `fotiavillage.*` | OP | 包含全部 FotiaVillage 权限。 |
| `fotiavillage.admin` | OP | 使用管理命令。 |
| `fotiavillage.item.give` | OP | 给予配置中的寿命道具。 |
| `fotiavillage.kill` | OP | 使用 `/fv kill`。 |
| `fotiavillage.stats` | 所有人 | 查看自己的交易统计。 |
| `fotiavillage.stats.others` | OP | 查看其他玩家交易统计。 |
| `fotiavillage.top` | 所有人 | 查看交易排行榜。 |
| `fotiavillage.trade.vip` | 无 | 匹配 `vip` 交易权限组。 |
| `fotiavillage.trade.svip` | 无 | 匹配 `svip` 交易权限组。 |
| `fotiavillage.trade.admin` | OP | 匹配 `admin` 交易权限组。 |

权限组按 `priority` 从高到低匹配。默认配置中 `admin` 组免经验消耗和冷却，`svip`、`vip` 组分别获得更低成本、更短冷却和额外周期交易额度。

## PlaceholderAPI 变量

变量前缀为 `%fotiavillage_...%`。

| 变量 | 说明 |
| --- | --- |
| `%fotiavillage_trades%` | 玩家总交易次数。 |
| `%fotiavillage_exp_spent%` | 玩家累计消耗经验。 |
| `%fotiavillage_rank%` | 玩家交易排行榜名次。 |
| `%fotiavillage_group%` | 当前匹配到的交易权限组名称。 |
| `%fotiavillage_group_priority%` | 当前交易权限组优先级。 |
| `%fotiavillage_daily_limit%` | 当前周期全局交易上限。 |
| `%fotiavillage_daily_used%` | 当前周期已使用的全局交易次数。 |
| `%fotiavillage_daily_remaining%` | 当前周期剩余全局交易次数。 |
| `%fotiavillage_player_level%` | 玩家当前等级。 |
| `%fotiavillage_top_1_name%` | 第 1 名玩家名，可将 `1` 换成其他名次。 |
| `%fotiavillage_top_1_trades%` | 第 1 名交易次数，可将 `1` 换成其他名次。 |
| `%fotiavillage_top_1_exp%` | 第 1 名累计消耗经验，可将 `1` 换成其他名次。 |

如果玩家不在线，玩家相关变量会返回空字符串；排行名次不存在时返回 `-`。

## 构建

```bash
mvn clean package
```

构建产物位于 `target/FotiaVillage-版本号.jar`。项目会把 SQLite JDBC 打入最终 jar，Paper API、PlaceholderAPI 和 MiniMessage 依赖按服务端提供处理。

## 发布

- GitHub Releases: <https://github.com/Ti-Avanti/FotiaVillage/releases>
- 最新版本下载: <https://github.com/Ti-Avanti/FotiaVillage/releases/latest>

## 目录

```text
src/main/java/gg/fotia/fotiavillage/
  command/        命令处理
  config/         配置读取和设置记录
  database/       SQLite 数据存储
  gui/            统计和排行榜 GUI
  language/       MiniMessage 语言服务
  lifespan/       村民寿命系统
  placeholder/    PlaceholderAPI 扩展
  stats/          交易统计
  trade/          交易控制、冷却、限制、倍率和绿宝石消耗
  update/         GitHub Release 更新检测
  villager/       村民数量与生成控制
```
