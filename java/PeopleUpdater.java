// 三种姓名更新方式共用的最小接口，供 UpdateBenchmark 统一计时。
public interface PeopleUpdater extends AutoCloseable {
    void reset() throws Exception;

    int updateNames() throws Exception;

    int verifyResult() throws Exception;

    int getRowCount();

    // 文件实现不保持打开的资源；数据库实现负责关闭连接。
    @Override
    default void close() throws Exception {
    }
}
