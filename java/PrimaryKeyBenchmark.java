import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

// 五十倍电影数据的主键查找实验，参数为file、postgresql或opengauss。
public class PrimaryKeyBenchmark {
    private static final int WARMUP_RUNS = 3;
    private static final int MEASURED_RUNS = 20;
    private static final int ROW_COUNT = 460200;
    private static final int[] IDS = {100, 230100, 460100, 460201};
    private static final String[] CASES = {"靠前", "中间", "靠后", "不存在"};
    private static final Logger OPEN_GAUSS_LOGGER = Logger.getLogger("org.opengauss");

    public static void main(String[] args) throws Exception {
        if (args.length > 1) {
            throw new IllegalArgumentException("用法：PrimaryKeyBenchmark [file|postgresql|opengauss]。");
        }
        String system = args.length == 0 ? "file" : args[0].toLowerCase(Locale.ROOT);
        if (system.equals("database")) {
            system = "postgresql";
        }
        DatasetScale scale = DatasetScale.FIFTYFOLD;
        DataManipulation dm = new DataFactory().createDataManipulation(system, scale);
        OPEN_GAUSS_LOGGER.setLevel(Level.WARNING);

        // 计时外从同一CSV读取预期记录，存在与不存在的情况都要核对。
        String[] expected = readExpected(scale);
        boolean file = dm instanceof FileManipulation;
        String display = file ? "文件（movies-50x.csv）"
                : system.equals("postgresql") ? "PostgreSQL（filmdb_old）" : "openGauss（filmdb）";
        System.out.println("系统：" + display);
        System.out.println("数据规模：五十倍，电影 " + ROW_COUNT + " 条");
        System.out.println("实验：按movieid查找，返回全部五字段");
        System.out.println("每个编号预热 " + WARMUP_RUNS + " 次，正式测量 " + MEASURED_RUNS + " 次");
        System.out.println(file ? "计时：打开文件、扫描解析、读取目标记录、关闭文件。"
                : "计时：创建语句、查询、读取结果、关闭语句；连接提前建立并复用。");
        if (!file) {
            printPlanAndCheckCount(system, scale);
        }

        ConnectionSupport connections = dm instanceof ConnectionSupport ? (ConnectionSupport) dm : null;
        try {
            if (connections != null) {
                connections.openConnection();
            }
            for (int index = 0; index < IDS.length; index++) {
                for (int run = 0; run < WARMUP_RUNS; run++) {
                    verify(dm.findMovieById(IDS[index]), expected[index]);
                }
                long[] nanos = new long[MEASURED_RUNS];
                for (int run = 0; run < MEASURED_RUNS; run++) {
                    long start = System.nanoTime();
                    String result = dm.findMovieById(IDS[index]);
                    nanos[run] = System.nanoTime() - start;
                    verify(result, expected[index]);
                }
                System.out.println(CASES[index] + "：movieid=" + IDS[index]);
                printTimes(nanos);
                System.out.println("  返回记录数：" + (expected[index].isEmpty() ? 0 : 1)
                        + (file ? "，文件扫描记录数：" + ((FileManipulation) dm).getScannedMovieCount() : ""));
            }
        } finally {
            if (connections != null) {
                connections.closeOpenConnection();
            }
        }
        System.out.println("每轮校验通过：存在编号的全部五字段与CSV一致，不存在编号返回空结果。");
    }

    private static String[] readExpected(DatasetScale scale) {
        FileManipulation baseline = new FileManipulation(scale);
        String[] expected = new String[IDS.length];
        for (int i = 0; i < IDS.length; i++) {
            expected[i] = baseline.findMovieById(IDS[i]);
            if (i < IDS.length - 1) {
                if (expected[i].isEmpty() || !expected[i].startsWith(IDS[i] + "\t")) {
                    throw new IllegalStateException("五十倍CSV缺少预期的电影编号：" + IDS[i]);
                }
                if (baseline.getScannedMovieCount() != IDS[i]) {
                    throw new IllegalStateException("五十倍CSV的记录顺序与实验位置不符。");
                }
            } else if (!expected[i].isEmpty() || baseline.getScannedMovieCount() != ROW_COUNT) {
                throw new IllegalStateException("五十倍CSV的总记录数或不存在编号校验失败。");
            }
        }
        return expected;
    }

    // 计时外检查表大小并查看实际执行计划，只执行读取操作。
    private static void printPlanAndCheckCount(String system, DatasetScale scale) throws Exception {
        Connection connection = system.equals("postgresql")
                ? new DatabaseManipulation().createPeopleUpdateConnection()
                : new OpenGaussDatabaseManipulation().createPeopleUpdateConnection();
        try (connection) {
            connection.setReadOnly(true);
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(15);
                try (ResultSet rows = statement.executeQuery("select count(*) from " + scale.moviesTable())) {
                    rows.next();
                    if (rows.getInt(1) != ROW_COUNT) {
                        throw new IllegalStateException("电影表记录数与五十倍CSV不符。");
                    }
                }
            }
            String sql = "explain analyze select movieid, title, country, year_released, runtime from "
                    + scale.moviesTable() + " where movieid = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, IDS[1]);
                statement.setQueryTimeout(15);
                System.out.println("执行计划（计时外，movieid=" + IDS[1] + "）：");
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        System.out.println("  " + rows.getString(1));
                    }
                }
            }
        }
    }

    private static void verify(String result, String expected) {
        if (!expected.equals(result)) {
            throw new IllegalStateException("主键查找的五字段结果与CSV不一致。");
        }
    }

    private static void printTimes(long[] nanos) {
        StringBuilder times = new StringBuilder();
        for (long value : nanos) {
            if (!times.isEmpty()) {
                times.append(", ");
            }
            times.append(String.format(Locale.ROOT, "%.3f", value / 1_000_000.0));
        }
        long[] sorted = Arrays.copyOf(nanos, nanos.length);
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        double median = sorted[middle - 1] / 2.0 + sorted[middle] / 2.0;
        System.out.println("  每次耗时（ms）：" + times);
        System.out.printf(Locale.ROOT, "  中位数：%.3f ms，最小：%.3f ms，最大：%.3f ms%n",
                median / 1_000_000.0, sorted[0] / 1_000_000.0, sorted[sorted.length - 1] / 1_000_000.0);
    }
}
