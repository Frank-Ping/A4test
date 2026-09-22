# Polar H10 记录仪（Android）

基于 [Polar 官方 BLE SDK](https://github.com/polarofficial/polar-ble-sdk) 的 Android 示例项目：
连接 Polar H10 心率带，实时显示心率，并将 **HR / RR、ECG（130Hz）、ACC（加速度）** 数据保存到本地
**SQLite** 数据库，支持历史会话查询。

> SDK 能力说明：[PolarH10.md](https://github.com/polarofficial/polar-ble-sdk/blob/master/documentation/products/PolarH10.md)
> （心率 1Hz 含 RR 间期；ECG 130Hz µV；加速度 25/50/100/200Hz mG）

## 功能

- 扫描附近 Polar 设备 / 手动输入设备 ID 连接
- 实时心率（bpm）与 RR 间期显示，连接即自动接收（BLE 标准心率通知），
  主界面带 60 秒滚动窗口的实时心率折线图（样式与历史页一致，超 10 分钟自动丢弃旧点）
- 一键开启/停止 ECG（130Hz）与加速度在线流（`requestStreamSettings` + `startEcgStreaming` / `startAccStreaming`）
- "记录会话"开关：会话期间所有流入数据写入 SQLite，结束自动写入结束时间
- 历史查询：会话列表（开始时间、时长、设备名称、各数据条数）→ 单次记录详情图表（MPAndroidChart）：
  - **HR**：单条折线图，横轴为经过时间（分:秒），上方显示平均/最低/最高心率，支持缩放
  - **RR**：逐次心跳折线图，横轴为心跳序号，上方显示平均/最低/最高 RR（ms）
  - **ECG**：可滚动波形图，默认 5 秒窗口，可滑动、缩放（超过 10 万点自动抽稀）
  - **ACC**：X/Y/Z 三轴固定颜色折线图，默认 10 秒窗口，可分别隐藏单轴
  - 支持删除会话（级联删除样本）

## 数据库结构（`polar_h10.db`）

| 表 | 说明 |
|---|---|
| `sessions` | id, device_id, start_time, end_time, note |
| `hr_samples` | id, session_id(外键), timestamp(ms), hr(bpm), rr(逗号分隔 ms) |
| `ecg_samples` | id, session_id(外键), timestamp(ns), voltage(µV) |
| `acc_samples` | id, session_id(外键), timestamp(ns), x, y, z(mG) |

ECG / ACC 采用事务批量插入；三张样本表均建有 `session_id` 索引，
删除会话时通过外键 `ON DELETE CASCADE` 级联清理。

## 环境与构建

- Android Studio（建议 Hedgehog 及以上），Gradle 8.7，AGP 8.5.2，Kotlin 2.0.20
- minSdk 26 / targetSdk 35（Polar BLE SDK 6.16.1 要求 minSdk ≥ 26）
- Polar BLE SDK：`com.github.polarofficial:polar-ble-sdk:6.16.1`（JitPack，RxJava3 版 API）

直接用 Android Studio **Open** 打开本目录，等待 Gradle Sync 完成后 **Run** 即可。

## 使用注意

1. **必须用真机**，模拟器没有 BLE。运行前打开手机蓝牙。
2. 首次启动会申请蓝牙权限（Android 12+：`BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`；
   更低版本：`ACCESS_FINE_LOCATION`）。
3. H10 需要贴身佩戴（电极湿润）才会广播心率；同时只允许一个 BLE 连接，
   请先在 Polar Beat / Polar Flow 等官方 App 中断开设备。
4. ECG / ACC 流依赖 `FEATURE_POLAR_SDK_MODE` 与 `FEATURE_POLAR_ONLINE_STREAMING`，
   连接成功后需等片刻（回调 `bleSdkFeatureReady`）再点"开始 ECG 流"。
5. ECG 以 130Hz 写入，1 分钟约 7800 行；长时间录制注意存储空间。
6. 数据库文件位于应用私有目录
   `/data/data/com.example.polarh10/databases/polar_h10.db`，
   可通过 Android Studio 的 **Device File Explorer / App Inspection** 导出查看。

## 代码结构

```
app/src/main/java/com/example/polarh10/
├── MainActivity.kt            # 扫描/连接/实时显示/流控制/会话记录
├── HistoryActivity.kt         # 历史会话列表（删除、进入详情）
├── SessionDetailActivity.kt   # 会话详情：统计 + HR/ECG/ACC 分页查询
├── adapter/
│   ├── SessionsAdapter.kt
│   └── SamplesAdapter.kt
├── db/
│   └── DatabaseHelper.kt      # SQLiteOpenHelper：建表、批量写入、历史查询
└── model/
    └── DataModels.kt          # Session/HrSample/EcgSample/AccSample/HrStats
```

## 导出历史记录

结束记录后，进入“历史记录”并打开一条会话，点击“导出本次记录（ZIP）”，在系统文件窗口中选择保存位置。取消选择不会导出；导出过程在后台线程执行，完成或失败时会提示。

ZIP 包含 session.csv、hr.csv、rr.csv、ecg.csv、acc.csv 和 README.txt。CSV 使用 UTF-8 BOM，空值保留为空字段；没有样本的数据类型仍会生成带表头的 CSV。导出直接读取数据库的全部原始样本，不使用图表抽稀后的数据。

- HR/RR 的 received_unix_ms 是手机接收通知的 Unix 毫秒时间，不是每次心跳的精确发生时间。
- RR 每个间隔单独一行，包含心跳序号、来源 HR 样本 ID 和间隔毫秒值；hr.csv 也保留原始 RR 列表。
- ECG/ACC 的 device_timestamp_ns 保留设备原始纳秒时间戳，不应当作 Unix 时间。
- ECG 单位为 µV，ACC 单位为 mG。用 Excel 分析时，建议将纳秒时间戳列作为文本导入，避免长整数精度损失。
- 仅导出已结束的记录。应用被系统终止的未结束会话会提示先结束记录。