import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

// PostgreSQL、openGauss 共用更新 SQL；两者连接配置仍各自保留。
// 只修改选定规模的工作副本，各规模人物基线只读。
public class PeopleDatabaseUpdater implements PeopleUpdater {
    private final Connection connection;
    private final int rowCount;
    private final int expectedChangedRows;
    private final String baselineTable;
    private final String workTable;

    public PeopleDatabaseUpdater(Connection connection, boolean openGauss, DatasetScale scale) throws SQLException {
        this.connection = connection;
        baselineTable = scale.peopleTable();
        workTable = scale.peopleWorkTable();
        try {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                if (openGauss) {
                    try (ResultSet result = statement.executeQuery("show behavior_compat_options")) {
                        result.next();
                        boolean acceptsEmpty = Arrays.stream(result.getString(1).split(","))
                                .anyMatch(option -> option.trim().equalsIgnoreCase("accept_empty_str"));
                        if (!acceptsEmpty) {
                            throw new SQLException("openGauss 须启用 accept_empty_str，以保留空 surname。");
                        }
                    }
                }
                // 只设置当前测速连接，不更改数据库全局配置。
                statement.execute("set synchronous_commit to on");
                statement.execute("""
                        create table if not exists %s (
                            peopleid integer primary key,
                            first_name text,
                            surname text not null,
                            born integer,
                            died integer,
                            gender varchar(1)
                        )
                        """.formatted(workTable));
                // 拒绝使用姓名长度受限的旧表，避免 Ttt 导致截断或失败。
                try (ResultSet result = statement.executeQuery("""
                        select count(*) from information_schema.columns
                        where table_schema = 'public' and table_name = '%s'
                          and column_name in ('first_name', 'surname') and data_type = 'text'
                        """.formatted(workTable.substring("public.".length())))) {
                    result.next();
                    if (result.getInt(1) != 2) {
                            throw new SQLException(workTable + " 的两个姓名字段须为 text；未覆盖现有表。");
                    }
                }
                try (ResultSet result = statement.executeQuery("""
                        select count(*),
                               coalesce(sum(case when position('T' in first_name) > 0
                                                  or position('T' in surname) > 0
                                                 then 1 else 0 end), 0)
                        from %s
                        """.formatted(baselineTable))) {
                    result.next();
                    rowCount = result.getInt(1);
                    expectedChangedRows = result.getInt(2);
                }
            }
            connection.commit();
        } catch (SQLException e) {
            rollbackAfterFailure(e);
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    @Override
    public int getRowCount() {
        return rowCount;
    }

    // 每轮计时外恢复六列基线并提交，NULL 和空字符串直接沿用原表。
    @Override
    public void reset() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("truncate table " + workTable);
            int inserted = statement.executeUpdate("""
                    insert into %s (peopleid, first_name, surname, born, died, gender)
                    select peopleid, first_name, surname, born, died, gender from %s
                    """.formatted(workTable, baselineTable));
            if (inserted != rowCount) {
                throw new SQLException("恢复基线的记录数不符。");
            }
            connection.commit();
        } catch (SQLException e) {
            rollbackAfterFailure(e);
            throw e;
        }
    }

    // 计时包含语句创建、执行 UPDATE、COMMIT 和语句关闭；连接已提前建立。
    @Override
    public int updateNames() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            int changed = statement.executeUpdate("""
                    update %s
                    set first_name = replace(first_name, 'T', 'Ttt'),
                        surname = replace(surname, 'T', 'Ttt')
                    where position('T' in first_name) > 0 or position('T' in surname) > 0
                    """.formatted(workTable));
            connection.commit();
            return changed;
        } catch (SQLException e) {
            rollbackAfterFailure(e);
            throw e;
        }
    }

    // 计时外对照选定的只读基线检查全部六列；is distinct from 区分 NULL 和空字符串。
    @Override
    public int verifyResult() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     select count(b.peopleid), coalesce(sum(case when
                         b.peopleid is null or p.peopleid is null
                         or b.first_name is distinct from replace(p.first_name::text, 'T', 'Ttt')
                         or b.surname is distinct from replace(p.surname::text, 'T', 'Ttt')
                         or b.born is distinct from p.born
                         or b.died is distinct from p.died
                         or b.gender is distinct from p.gender
                         then 1 else 0 end), 0)
                     from %s b full outer join %s p
                         on b.peopleid = p.peopleid
                     """.formatted(workTable, baselineTable))) {
            result.next();
            if (result.getInt(1) != rowCount || result.getInt(2) != 0) {
                throw new SQLException("更新校验失败：记录数、姓名或其余字段与预期不符。");
            }
            // 结束只读校验事务，避免下一轮开始前仍持有事务。
            connection.commit();
            return expectedChangedRows;
        } catch (SQLException e) {
            rollbackAfterFailure(e);
            throw e;
        }
    }

    private void rollbackAfterFailure(SQLException failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
