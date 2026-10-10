# 文件与数据库操作比较

**结果看 [report.md](report.md)，数据在 [导出的文件](导出的文件/README.md)，代码分工看 [java/README.md](java/README.md)。** 点击 Code → Download ZIP 下载。

`java` 保留完整本地项目，包括源码、IDEA/VS Code 配置、驱动和历史运行输出。用 IDEA 打开这个目录，选择 JDK 21，再选择已有的运行配置；驱动已在 `java/lib` 中配置好。`out` 是之前生成的文件，运行时由 IDEA 重新编译源码。

先看这四个测速入口：

| 主类 | 负责什么 | 参数示例 |
|---|---|---|
| Benchmark | 标题检索 | file man 50x |
| UpdateBenchmark | 姓名更新 | file 50x |
| PrimaryKeyBenchmark | 五十倍主键查找 | file |
| ImdbBenchmark | IMDb全量标题检索 | file man |

工作目录设为 `java`。文件版直接用包内 CSV；数据库版在两个 `*DatabaseManipulation.java` 中填写自己的连接信息，公开版密码为空。把参数里的 `file` 换成 `postgresql` 或 `opengauss` 即可切换。

标题和姓名实验可选 `1x`、`10x`、`50x`。五十倍 VM 选项用 `-Xmx1024m`，IMDb 用 `-Xmx512m`。

数据库先建好源码中配置的库，再运行 `DatasetBuilder`，程序参数填 `import postgresql 50x`，导入对应 CSV（系统和规模按需换）；openGauss 的 `behavior_compat_options` 需包含 `accept_empty_str`。IMDb 数据[下载后解压](导出的文件/README.md)，按 TSV 表头九列导入两库的 `imdb.public.title_basics`：UTF-8、Tab 分隔、关闭引号解析、`\N` 作 NULL。
