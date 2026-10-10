# 文件与数据库操作比较

结果看[报告](report.md)，代码分工看[这里](java/README.md)。

**下载：点击 Code → Download ZIP，再解压。六份实验 CSV 和 JDBC 驱动都在包里。**

## 先跑文件版

Windows 安装 JDK 21 后，双击 `运行实验.cmd`，选实验，系统填 `file`。标题检索、姓名更新可选 `1x`、`10x`、`50x`；主键查找固定用五十倍。脚本会编译源码并设置工作目录和内存，不需要先配 IDEA。

IMDb 文件约 1.12 GB，按官方条款不随仓库转载。选 IMDb 文件实验时会自动下载并解压到“导出的文件”，**首次需要联网**；也可以先双击 `下载IMDb.cmd`。官方数据每天更新，下载后的数量可能与报告略有不同。

## 再跑数据库版

1. 先启动自己的 PostgreSQL / openGauss，并建好实验数据库。在 `java/DatabaseManipulation.java`、`java/OpenGaussDatabaseManipulation.java` 填地址、端口、库名、用户名和密码。包内密码为空。
2. openGauss 要保留空字符串。在新建的实验库可执行 `ALTER DATABASE filmdb SET behavior_compat_options = 'accept_empty_str';`，随后重新连接；库名按自己的配置改。如果已有兼容选项，把 `accept_empty_str` 追加到原值中。
3. 双击运行脚本，选“导入CSV到数据库”，再选数据库和规模。它会建表、导入包内 CSV；已有非空表只核对，不覆盖。导入后就能选择同系统、同规模测速。
4. IMDb 另建 `imdb` 数据库，执行 [建表SQL](sql/imdb-schema.sql)，用 DataGrip 导入 `title.basics.tsv`：UTF-8、Tab 分隔、首行为表头、按九列顺序映射，关闭引号解析，`\N` 作 NULL。导入后选 IMDb 数据库实验。

想用 IDEA 也可以：打开 `java`，把 `lib` 两个 JAR 加入依赖，工作目录设为 `java`。

| 主类 | 程序参数示例 |
|---|---|
| Benchmark | file man 50x |
| UpdateBenchmark | file 50x |
| PrimaryKeyBenchmark | file |
| ImdbBenchmark | file man |
| DatasetBuilder | import postgresql 50x |

把 `file` 换成 `postgresql` 或 `opengauss` 即可切换。五十倍用 `-Xmx1024m`，IMDb 用 `-Xmx512m`；IMDb 只预热一次、测三次。
