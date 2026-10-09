
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class OpenGaussDatabaseManipulation implements DataManipulation, ConnectionSupport {
    private Connection con = null;
    // 由 openConnection 置为 true，表示查询复用已经建立的连接，不再自己建立和关闭。
    private boolean reuseConnection = false;
    private final String moviesTable;

    public OpenGaussDatabaseManipulation() {
        this(DatasetScale.ORIGINAL);
    }

    // 所选规模用于实验查询，其他课程示例仍沿用原表。
    public OpenGaussDatabaseManipulation(DatasetScale scale) {
        moviesTable = scale.moviesTable();
    }

    private String host = "127.0.0.1";
    private String dbname = "filmdb";
    private String user = "gauss";
    private String pwd = "";
    private String port = "26000";
    private String driver = "org.opengauss.Driver";
    private String urlPrefix = "jdbc:opengauss://";


    // 姓名更新测速使用独立连接，沿用本类配置，由调用者负责关闭。
    public Connection createPeopleUpdateConnection() throws SQLException {
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            throw new SQLException("找不到 openGauss JDBC 驱动。", e);
        }
        return DriverManager.getConnection(urlPrefix + host + ":" + port + "/" + dbname, user, pwd);
    }

    // IMDb大数据入口用独立只读连接；复用现有地址和账号，允许指定数据库。
    public Connection createImdbConnection(String database) throws SQLException {
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            throw new SQLException("找不到 openGauss JDBC 驱动。", e);
        }
        String target = database == null ? dbname : database;
        if (!target.matches("[A-Za-z0-9_-]+")) {
            throw new SQLException("数据库名应只含字母、数字、下划线或连字符。");
        }
        return DriverManager.getConnection(urlPrefix + host + ":" + port + "/" + target
                + "?connectTimeout=10&socketTimeout=50", user, pwd);
    }

    private void getConnection() {
        if (pwd.isEmpty()) {
            System.err.println("请先填写 pwd 中的数据库密码。");
            System.exit(1);
        }
        try {
            Class.forName(driver);

        } catch (Exception e) {
            System.err.println("Cannot find the database driver. Check CLASSPATH.");
            System.exit(1);
        }

        try {
            String url = urlPrefix + host + ":" + port + "/" + dbname;
            con = DriverManager.getConnection(url, user, pwd);

        } catch (SQLException e) {
            System.err.println("Database connection failed");
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }


    private void closeConnection() {
        if (con != null) {
            try {
                con.close();
                con = null;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // 只建立并关闭一次连接，用于单独测量连接开销。
    @Override
    public void checkConnection() {
        getConnection();
        closeConnection();
    }

    // 建立并保持连接；之后的 findMoviesByTitle 会复用它，计时里就不包含连接。
    @Override
    public void openConnection() {
        getConnection();
        reuseConnection = true;
    }

    @Override
    public void closeOpenConnection() {
        reuseConnection = false;
        closeConnection();
    }

    @Override
    public String findMovieById(int id) {
        boolean reuse = reuseConnection && con != null;
        if (!reuse) {
            getConnection();
        }
        // 按主键查找并读取完整五列，未命中返回空字符串。
        String sql = "select movieid, title, country, year_released, runtime from "
                + moviesTable + " where movieid = ?";
        try (PreparedStatement statement = con.prepareStatement(sql)) {
            statement.setInt(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return "";
                }
                String runtime = rows.getString("runtime");
                return new StringBuilder().append(rows.getInt("movieid")).append("\t")
                        .append(rows.getString("title")).append("\t")
                        .append(rows.getString("country")).append("\t")
                        .append(rows.getInt("year_released")).append("\t")
                        .append(runtime == null ? "" : runtime).append("\n").toString();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("电影主键查找失败。", e);
        } finally {
            if (!reuse) {
                closeConnection();
            }
        }
    }


    @Override
    public String findMoviesByTitle(String title) {
        boolean reuse = reuseConnection && con != null;
        if (!reuse) {
            getConnection();
        }
        String sql = "select movieid, title, country, year_released, runtime from " + moviesTable;
        if (!title.isEmpty()) {
            sql += " where position(? in title) > 0";
        }
        StringBuilder result = new StringBuilder();
        try (PreparedStatement statement = con.prepareStatement(sql)) {
            if (!title.isEmpty()) {
                statement.setString(1, title);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String runtime = rows.getString("runtime");
                    result.append(rows.getInt("movieid")).append("\t")
                            .append(rows.getString("title")).append("\t")
                            .append(rows.getString("country")).append("\t")
                            .append(rows.getInt("year_released")).append("\t")
                            .append(runtime == null ? "" : runtime).append("\n");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("电影标题检索失败。", e);
        } finally {
            if (!reuse) {
                closeConnection();
            }
        }
        return result.toString();
    }
}
