# 代码说明

**先看这三个入口，报告里的实验就是从这里跑的：**

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
