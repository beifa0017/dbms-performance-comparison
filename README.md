# 文件与数据库操作比较

比较文件、PostgreSQL和openGauss的标题检索、姓名更新和主键查找，另用IMDb大表测试标题检索。

结果看[报告](report.md)，各文件负责什么看[代码说明](java/README.md)。

## 怎么跑

用IDEA打开`java`，使用JDK 21，添加PostgreSQL JDBC 42.7.13和openGauss JDBC 6.0.6驱动。在两个`*DatabaseManipulation.java`中填写自己的地址、端口、数据库名、用户名和密码。

数据文件放在与`java`同级的“导出的文件”目录，运行工作目录设为`java`。

| 主类 | 程序参数示例 | 实验 |
|---|---|---|
| Benchmark | file man 50x | lab1标题检索 |
| UpdateBenchmark | file 50x | lab1姓名更新 |
| PrimaryKeyBenchmark | file | 五十倍主键查找 |
| ImdbBenchmark | file man | IMDb全量标题检索 |

把`file`换成`postgresql`或`opengauss`即可切换实现。

lab1先准备课程原表和`movies.csv`、`people.csv`，再运行`DatasetBuilder`，参数分别填`10x`和`50x`，生成两组扩充数据。五十倍的VM选项为`-Xmx1024m`；openGauss姓名实验需启用`accept_empty_str`以保留空字符串。

IMDb使用[官方数据](https://datasets.imdbws.com/)的`title.basics.tsv`，两库导入到`imdb.public.title_basics`；VM选项为`-Xmx512m`。这一组预热1次、测量3次，少量导入差异只记录。
