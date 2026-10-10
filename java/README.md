# 代码说明

**先看四个实验入口：**

- `Benchmark.java`：lab1标题检索，分别测完整操作和复用连接查询。
- `UpdateBenchmark.java`：lab1姓名更新，恢复副本、计时、检查结果。
- `PrimaryKeyBenchmark.java`：五十倍主键查找，打印数据库执行计划。
- `ImdbBenchmark.java`：IMDb全量标题检索，分批读取结果，记录耗时和匹配数量。

**具体查找和更新在这里：**

- `FileManipulation.java`：在电影CSV中按标题或编号查找。
- `DatabaseManipulation.java`、`OpenGaussDatabaseManipulation.java`：两库的连接配置和电影查询。
- `PeopleFileUpdater.java`：在人物文件副本上修改姓名并检查。
- `PeopleDatabaseUpdater.java`：两库共用的姓名更新、提交和检查。

**剩下的是准备和辅助代码：**

- `DatasetBuilder.java`：生成lab1扩充CSV，或用 `import` 把包内CSV导入选定数据库。
- `DatasetScale.java`：选择对应规模的文件路径和表名。
- `DataFactory.java`：按参数选择查询实现。
- `DataManipulation.java`、`PeopleUpdater.java`：查询和姓名更新的共用接口。
- `ConnectionSupport.java`：让测速入口控制连接的建立、复用和关闭。
