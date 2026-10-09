import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

// IMDb全量标题检索：预热一次、测量三次，读取全部匹配记录的九字段。
public class ImdbBenchmark {
    private static final String[] COLUMNS = {"tconst", "titleType", "primaryTitle", "originalTitle",
            "isAdult", "startYear", "endYear", "runtimeMinutes", "genres"};
    private static final Path TSV = Path.of("../导出的文件/title.basics.tsv");
    private static final String DATABASE = "imdb";
    private static final String TABLE = "public.title_basics";
    private static final int MEASURED_RUNS = 3;
    private static final int OPERATION_SECONDS = 45;
    private static final int TOTAL_SECONDS = 180;
    private static final BigInteger MODULUS = BigInteger.ONE.shiftLeft(256);
    private static final Logger OPEN_GAUSS_LOGGER = Logger.getLogger("org.opengauss");

    public static void main(String[] args) throws Exception {
        if (args.length > 2) {
            throw new IllegalArgumentException("用法：ImdbBenchmark file|postgresql|opengauss [关键词]。");
        }
        String system = args.length == 0 ? "file" : args[0].toLowerCase(Locale.ROOT);
        if (!List.of("file", "postgresql", "opengauss").contains(system)) {
            throw new IllegalArgumentException("请选择file、postgresql或opengauss。");
        }
        String keyword = args.length > 1 ? args[1] : "man";
        if (keyword.isEmpty()) {
            throw new IllegalArgumentException("请填写检索关键词。");
        }
        OPEN_GAUSS_LOGGER.setLevel(Level.WARNING);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TOTAL_SECONDS);
        boolean file = system.equals("file");
        System.out.println("系统：" + system + "；实验：IMDb全量primaryTitle包含检索");
        System.out.println("关键词：" + keyword + "；读取全部匹配记录的九字段，无LIMIT。");
        if (file) {
            System.out.printf(Locale.ROOT, "文件：%s（%.3f GB）%n", TSV.toAbsolutePath().normalize(), Files.size(TSV) / 1_000_000_000.0);
        } else {
            System.out.println("数据库/表：" + DATABASE + "/" + TABLE + "，复用只读连接。");
        }
        System.out.println("预热1次，测量3次；单次最多45秒，整次约180秒，不自动重试。");
        System.out.println("计时含检索、读取和汇总结果；连接建立、打印不计时。导入遗漏不阻止测速。");

        Connection connection = null;
        ScheduledExecutorService watchdog = null;
        Result last = null;
        Exception failure = null;
        int completed = 0;
        boolean resultChanged = false;
        long[] nanos = new long[MEASURED_RUNS];
        try {
            String[] columns = null;
            if (!file) {
                connection = system.equals("postgresql")
                        ? new DatabaseManipulation().createImdbConnection(DATABASE)
                        : new OpenGaussDatabaseManipulation().createImdbConnection(DATABASE);
                connection.setReadOnly(true);
                connection.setAutoCommit(false);
                watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
                    Thread thread = new Thread(task, "imdb-time-limit");
                    thread.setDaemon(true);
                    return thread;
                });
                connection.setNetworkTimeout(watchdog, 50000);
                Connection active = connection;
                watchdog.schedule(() -> {
                    try {
                        active.abort(Runnable::run);
                    } catch (SQLException ignored) {
                        // 读取循环、SQL及网络超时也会结束操作。
                    }
                }, Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                columns = databaseColumns(connection, deadline);
            }
            System.out.println("预热中…");
            Result warmup = runOnce(file, connection, columns, keyword, operationDeadline(deadline));
            for (int run = 0; run < MEASURED_RUNS; run++) {
                long operationEnd = operationDeadline(deadline);
                long start = System.nanoTime();
                Result result = runOnce(file, connection, columns, keyword, operationEnd);
                nanos[completed++] = System.nanoTime() - start;
                resultChanged |= !result.equals(warmup);
                last = result;
                System.out.println("完成第" + completed + "/" + MEASURED_RUNS + "次测量。");
            }
        } catch (Exception stopped) {
            failure = stopped;
            System.err.println("本批停止：" + stopped.getMessage());
        } finally {
            if (watchdog != null) {
                watchdog.shutdownNow();
            }
            if (connection != null) {
                try {
                    if (!connection.isClosed()) {
                        connection.rollback();
                    }
                } catch (SQLException cleanupFailure) {
                    if (failure == null) {
                        failure = cleanupFailure;
                    } else {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                try {
                    connection.close();
                } catch (SQLException cleanupFailure) {
                    if (failure == null) {
                        failure = cleanupFailure;
                    } else {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
        }
        if (completed == 0) {
            throw new IllegalStateException("没有完成正式测量。", failure);
        }
        // 只统计完整完成的轮次，超时或失败的那一轮不作为耗时样本。
        System.out.println("本批完成测量：" + completed + "/" + MEASURED_RUNS + "次");
        printTimes(Arrays.copyOf(nanos, completed));
        if (file) {
            System.out.println("扫描记录数：" + last.scannedRows());
            System.out.println("跳过格式不正确的记录：" + last.skippedRows());
        }
        System.out.println("匹配记录数：" + last.matchedRows());
        System.out.println("九字段结果摘要：" + last.fingerprint().substring(0, 16));
        System.out.println("不同系统的匹配数量或摘要可能因少量导入遗漏而不同，保留各自实测结果。");
        if (resultChanged) {
            System.out.println("提示：本系统各轮结果发生变化，耗时仍保留。");
        }
    }

    private static Result runOnce(boolean file, Connection connection, String[] columns, String keyword, long deadline) throws Exception {
        return file ? readFile(TSV, keyword, deadline) : query(connection, columns, keyword, deadline);
    }

    // TSV有表头，以制表符分列；顺序读到文件末尾，不把整个文件放进内存。
    static Result readFile(Path path, String keyword, long deadline) throws Exception {
        Fingerprint fingerprint = new Fingerprint();
        long scanned = 0;
        long skipped = 0;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null || !Arrays.equals(header.replaceFirst("^\\uFEFF", "").split("\t", -1), COLUMNS)) {
                throw new IOException("TSV表头与IMDb title.basics的九列不一致。");
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if ((scanned & 1023) == 0) {
                    checkTime(deadline);
                }
                scanned++;
                String[] values = line.split("\t", -1);
                if (values.length != COLUMNS.length) {
                    skipped++;
                    continue;
                }
                if (values[2].contains(keyword)) {
                    fingerprint.add(values);
                }
            }
        }
        checkTime(deadline);
        return new Result(scanned, fingerprint.rows, skipped, fingerprint.finish());
    }

    private static String[] databaseColumns(Connection connection, long deadline) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(remainingSeconds(deadline));
            try (ResultSet rows = statement.executeQuery("select * from " + TABLE + " limit 0")) {
                ResultSetMetaData metadata = rows.getMetaData();
                String[] names = new String[COLUMNS.length];
                for (int column = 0; column < COLUMNS.length; column++) {
                    String wanted = COLUMNS[column].toLowerCase(Locale.ROOT);
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        if (metadata.getColumnName(i).replace("_", "").toLowerCase(Locale.ROOT).equals(wanted)) {
                            names[column] = '"' + metadata.getColumnName(i).replace("\"", "\"\"") + '"';
                            break;
                        }
                    }
                    if (names[column] == null) {
                        throw new SQLException("title_basics缺少字段：" + COLUMNS[column]);
                    }
                }
                return names;
            }
        }
    }

    // 分批读取完整查询结果；计时结束前读完九字段，不限制返回条数。
    static Result query(Connection connection, String[] columns, String keyword, long deadline) throws Exception {
        String sql = "select " + String.join(", ", columns) + " from " + TABLE
                + " where position(? in " + columns[2] + ") > 0";
        Fingerprint fingerprint = new Fingerprint();
        try (PreparedStatement statement = connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            statement.setString(1, keyword);
            statement.setFetchSize(1000);
            statement.setQueryTimeout(remainingSeconds(deadline));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if ((fingerprint.rows & 1023) == 0) {
                        checkTime(deadline);
                    }
                    String[] values = new String[COLUMNS.length];
                    for (int i = 0; i < values.length; i++) {
                        values[i] = rows.getString(i + 1);
                    }
                    fingerprint.add(values);
                }
            }
        }
        checkTime(deadline);
        return new Result(-1, fingerprint.rows, 0, fingerprint.finish());
    }

    private static long operationDeadline(long totalDeadline) throws IOException {
        checkTime(totalDeadline);
        return Math.min(totalDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(OPERATION_SECONDS));
    }

    private static int remainingSeconds(long deadline) throws IOException {
        checkTime(deadline);
        long seconds = (deadline - System.nanoTime() + 999_999_999L) / 1_000_000_000L;
        return (int) Math.max(1, Math.min(OPERATION_SECONDS, seconds));
    }

    static void checkTime(long deadline) throws IOException {
        if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) {
            throw new IOException("达到运行时间限制。");
        }
    }

    static void printTimes(long[] nanos) {
        List<String> times = new ArrayList<>();
        for (long value : nanos) {
            times.add(String.format(Locale.ROOT, "%.3f", value / 1_000_000.0));
        }
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        double median = sorted.length % 2 == 0
                ? sorted[middle - 1] / 2.0 + sorted[middle] / 2.0 : sorted[middle];
        System.out.println("每次耗时（ms）：" + String.join(", ", times));
        System.out.printf(Locale.ROOT, "中位数：%.3f ms；最小：%.3f ms；最大：%.3f ms%n",
                median / 1_000_000.0, sorted[0] / 1_000_000.0, sorted[sorted.length - 1] / 1_000_000.0);
    }

    record Result(long scannedRows, long matchedRows, long skippedRows, String fingerprint) { }

    // 完整九字段逐行计算SHA-256并累加，便于显示结果特征，不强制跨系统相同。
    static class Fingerprint {
        private final MessageDigest digest;
        private BigInteger sum = BigInteger.ZERO;
        private long rows;

        Fingerprint() throws Exception {
            digest = MessageDigest.getInstance("SHA-256");
        }

        void add(String[] values) {
            digest.reset();
            for (String value : values) {
                if (value == null || value.equals("\\N")) {
                    digest.update(ByteBuffer.allocate(4).putInt(-1).array());
                } else {
                    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                    digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                    digest.update(bytes);
                }
            }
            sum = sum.add(new BigInteger(1, digest.digest())).mod(MODULUS);
            rows++;
        }

        String finish() {
            return String.format(Locale.ROOT, "%064x", sum);
        }
    }
}
