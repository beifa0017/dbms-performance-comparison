# 文件与数据库操作比较

用课程lab1的电影和人物数据，比较CSV文件、PostgreSQL和openGauss的标题检索、姓名更新和主键查找。

完整结果看[报告](report.md)，各文件负责什么看[代码说明](java/README.md)。

## 实验里看到了什么

- 标题检索：提前建立并复用连接后，两个数据库都比文件完整检索快。
- 姓名更新：扩充后文件耗时较低，但文件写回和数据库同步提交提供的保存保证不同。
- 主键查找：文件找靠前记录很快，找中间、靠后或不存在的编号时，两库的索引查找更快。

这些是本机配置下的测量结果，具体数据和计时范围见报告。

## 怎么运行

1. 用IDEA打开源码，使用JDK 21，添加PostgreSQL JDBC 42.7.13和openGauss JDBC 6.0.6两个驱动。
2. 在DatabaseManipulation、OpenGaussDatabaseManipulation中填写自己的连接配置。
3. 将lab1导出的movies.csv、people.csv放在与java同级的“导出的文件”目录。两个数据库的原规模表按lab1准备；运行DatasetBuilder，参数填50x，准备扩充CSV及两库基线。
4. 工作目录设为java，选择下面的主类运行：

| 主类 | 程序参数示例 | 做什么 |
|---|---|---|
| Benchmark | file man 50x | 标题检索 |
| UpdateBenchmark | file 50x | 姓名更新 |
| PrimaryKeyBenchmark | file | 五十倍主键查找 |

把file换成postgresql或opengauss即可切换实现；五十倍运行的VM选项为-Xmx1024m。驱动和CSV需在本地准备。

新增IMDb全量标题检索用`ImdbBenchmark`，参数为`file man`、`postgresql man`或`opengauss man`。文件读完整TSV，两库查`imdb.public.title_basics`。数据来自[IMDb官方](https://datasets.imdbws.com/)，TSV放在与java同级的“导出的文件”目录，两库需已完成导入。VM选项`-Xmx512m`；预热1次、测量3次。导入造成的少量差异只记录，不中止测速。报告已加入这组全量结果。
