# 文件与数据库操作比较

**结果看 [report.md](report.md)，数据在 [导出的文件](导出的文件/README.md)。** 点击 Code → Download ZIP 即可下载源码和六份 CSV。

先看这四个测速入口：

| 主类 | 负责什么 | 参数示例 |
|---|---|---|
| Benchmark | 标题检索 | file man 50x |
| UpdateBenchmark | 姓名更新 | file 50x |
| PrimaryKeyBenchmark | 五十倍主键查找 | file |
| ImdbBenchmark | IMDb全量标题检索 | file man |

`FileManipulation` 负责文件查找，两个 `*DatabaseManipulation` 负责连接和数据库查找；两个 `People*Updater` 负责姓名更新。`DatasetBuilder` 准备数据，其他类负责规模选择、接口和连接控制。

用 IDEA 打开 `java`，选择 JDK 21，工作目录设为 `java`。文件版直接用包内 CSV；数据库版需添加 [PostgreSQL JDBC](https://jdbc.postgresql.org/download/) 42.7.13 和 [openGauss JDBC](https://opengauss.org/en/download/) 6.0.6，并在两个 `*DatabaseManipulation.java` 中填写自己的连接信息。把参数里的 `file` 换成 `postgresql` 或 `opengauss` 即可切换。

标题和姓名实验可选 `1x`、`10x`、`50x`。五十倍 VM 选项用 `-Xmx1024m`，IMDb 用 `-Xmx512m`。

数据库先建好源码中配置的库，再运行 `DatasetBuilder`，程序参数填 `import postgresql 50x`，导入对应 CSV（系统和规模按需换）；openGauss 的 `behavior_compat_options` 需包含 `accept_empty_str`。IMDb 数据[下载后解压](导出的文件/README.md)，按 TSV 表头九列导入两库的 `imdb.public.title_basics`：UTF-8、Tab 分隔、关闭引号解析、`\N` 作 NULL。
