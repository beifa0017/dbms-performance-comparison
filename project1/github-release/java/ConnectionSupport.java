// 两个数据库实现共有的连接控制，供 Benchmark 单独测量连接开销，以及复用已经建立的连接。
public interface ConnectionSupport {

    // 建立并立即关闭一次连接，用于单独测量连接开销。
    public void checkConnection();

    // 建立并保持连接，之后的查询会复用它。
    public void openConnection();

    // 关闭保持中的连接。
    public void closeOpenConnection();
}