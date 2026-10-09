# 代码说明

**旧报告的三个实验入口：**

- **`Benchmark.java`**：跑标题检索，记录耗时，再检查查出来的结果。
- **`UpdateBenchmark.java`**：跑姓名更新，把名字和姓氏里的大写 `T` 换成 `Ttt`，同样会计时和检查结果。
- **`PrimaryKeyBenchmark.java`**：按电影编号查找，比较文件扫描和数据库主键查找，并打印数据库的执行计划。

这三个入口都能按参数选择文件、PostgreSQL或openGauss。

**具体怎么查、怎么改，主要看下面四处：**

- `FileManipulation.java`：从电影CSV里按标题或编号找记录。
- `DatabaseManipulation.java`、`OpenGaussDatabaseManipulation.java`：分别连接PostgreSQL、openGauss，按标题或编号查电影。
- `PeopleFileUpdater.java`：在人物文件副本上改姓名，改完写回文件并检查。
- `PeopleDatabaseUpdater.java`：在数据库的工作表上改姓名，改完提交并检查。两种数据库共用这份代码。

扩充数据看这两个文件：

- `DatasetBuilder.java`：准备同一批扩充数据，保存成CSV，再导入两个数据库。
- `DatasetScale.java`：按数据规模找到对应的CSV和数据库表。

剩下的是辅助代码：

- `DataManipulation.java`、`PeopleUpdater.java`：分别是数据操作和姓名更新的共用接口。
- `DataFactory.java`：按参数选用文件或数据库的实现。
- `ConnectionSupport.java`：让测速入口控制连接，用来测连接开销和复用连接。

**新IMDb全量检索看 `ImdbBenchmark.java`：**

文件读取完整`title.basics.tsv`，两个数据库直接查`imdb.public.title_basics`，返回全部匹配记录的九字段。文件逐行读、数据库分批读，不把全部结果留在内存里。

IDEA选择`ImdbBenchmark 文件`、`ImdbBenchmark PostgreSQL`、`ImdbBenchmark openGauss`依次运行即可，三个配置都已填好关键词`man`。工作目录是`java`，VM选项为`-Xmx512m`。如果直接运行主类，参数分别为`file man`、`postgresql man`、`opengauss man`。

每种方式预热1次、测量3次，单次45秒、整次约180秒限时。少量导入遗漏或结果差异只显示信息，不会拦住测速；超时不会自动重试，未完成的轮次不计入统计。`createImdbConnection()`复用原地址和账号，选择`imdb`并设置连接超时。
