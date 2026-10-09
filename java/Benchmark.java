import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

// 统一测速入口：文件、PostgreSQL、openGauss 使用同一套预热和重复测量规则。
// 用法：Benchmark <file|postgresql|opengauss> [关键词] [1x|10x|50x|100x]
// 例如：Benchmark file man 10x；database 仍是 postgresql 的别名。
//
// 数据库端会分别测量三种边界：
//   1. 连接开销：只建立并关闭一次连接。
//   2. 完整操作：建立连接、执行查询、读完全部结果、关闭连接。
//   3. 复用连接查询：计时含语句创建、执行、读结果整理和语句关闭。
// 文件端只有完整操作，计时包含打开、读取、解析、匹配、整理结果和关闭。
public class Benchmark {

    private static final int WARMUP_RUNS = 3;
    private static final int MEASURED_RUNS = 20;
    private static final int CONNECT_RUNS = 5;
    // 保留引用，让驱动日志设置在整个测速期间有效。
    private static final Logger OPEN_GAUSS_LOGGER = Logger.getLogger("org.opengauss");

    public static void main(String[] args) {
        String system = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "database";
        String keyword = args.length > 1 ? args[1] : "man";
        if (args.length > 3) {
            throw new IllegalArgumentException("用法：Benchmark file|postgresql|opengauss [关键词] [规模]。");
        }
        DatasetScale scale = args.length > 2 ? DatasetScale.parse(args[2]) : DatasetScale.ORIGINAL;
        DataManipulation dm = new DataFactory().createDataManipulation(system, scale);

        // 隐藏普通连接信息，仍显示驱动的警告和错误；设置在计时之外。
        OPEN_GAUSS_LOGGER.setLevel(Level.WARNING);

        System.out.println("系统：" + displayName(system, scale));
        System.out.println("数据规模：" + scale.displayName());
        System.out.println("实验：标题包含检索，返回全部匹配记录");
        System.out.println("关键词：" + keyword);
        System.out.println("预热 " + WARMUP_RUNS + " 次，正式测量 " + MEASURED_RUNS + " 次");

        ConnectionSupport connections = (dm instanceof ConnectionSupport) ? (ConnectionSupport) dm : null;

        String result = "";

        // 1. 连接开销。多测一次并把第一次作为预热丢弃，因为它包含驱动类加载。
        if (connections != null) {
            long[] connectNanos = measureConnect(connections, CONNECT_RUNS + 1);
            System.out.printf("连接开销预热：%.3f ms（含驱动类加载，不计入）%n", connectNanos[0] / 1_000_000.0);
            printTimes("连接开销（只建立并关闭连接）", Arrays.copyOfRange(connectNanos, 1, connectNanos.length));
        }

        // 计时外读取同规模 CSV 作为结果预期，每轮按全部五字段核对。
        FileManipulation baseline = new FileManipulation(scale);
        String[] expected = sortedLines(baseline.findMoviesByTitle(keyword));
        if (baseline.getScannedMovieCount() != 9204 * scale.factor()) {
            throw new IllegalStateException("电影 CSV 基线记录数与选定规模不符。");
        }

        // 2. 完整操作。
        for (int i = 0; i < WARMUP_RUNS; i++) {
            result = dm.findMoviesByTitle(keyword);
            verifyResult(result, expected);
        }
        long[] wholeNanos = new long[MEASURED_RUNS];
        for (int i = 0; i < MEASURED_RUNS; i++) {
            long start = System.nanoTime();
            result = dm.findMoviesByTitle(keyword);
            long end = System.nanoTime();
            wholeNanos[i] = end - start;
            verifyResult(result, expected);
        }
        printTimes(system.toLowerCase().contains("file")
                ? "完整操作（打开、读取、解析、匹配、整理结果、关闭文件）"
                : "完整操作（建立连接、查询、读完全部结果、关闭连接）", wholeNanos);

        // 3. 复用连接查询：连接先在计时外建立好，再重复查询。
        if (connections != null) {
            connections.openConnection();
            long[] queryNanos;
            try {
                for (int i = 0; i < WARMUP_RUNS; i++) {
                    result = dm.findMoviesByTitle(keyword);
                    verifyResult(result, expected);
                }
                queryNanos = new long[MEASURED_RUNS];
                for (int i = 0; i < MEASURED_RUNS; i++) {
                    long start = System.nanoTime();
                    result = dm.findMoviesByTitle(keyword);
                    long end = System.nanoTime();
                    queryNanos[i] = end - start;
                    verifyResult(result, expected);
                }
            } finally {
                connections.closeOpenConnection();
            }
            printTimes("复用连接查询（创建语句、查询、读取整理结果、关闭语句）", queryNanos);
        }

        // 统计、打印和结果摘要都在计时之外。
        System.out.println("电影基线记录数：" + baseline.getScannedMovieCount());
        System.out.println("匹配行数：" + countLines(result));
        System.out.println("结果摘要（排序后 SHA-256 前 16 位）：" + digest(result));
        System.out.println("每轮结果校验通过：全部五字段与同规模 CSV 的匹配记录一致。");
    }

    private static long[] measureConnect(ConnectionSupport connections, int runs) {
        long[] nanos = new long[runs];
        for (int i = 0; i < runs; i++) {
            long start = System.nanoTime();
            connections.checkConnection();
            long end = System.nanoTime();
            nanos[i] = end - start;
        }
        return nanos;
    }

    private static void printTimes(String title, long[] nanos) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < nanos.length; i++) {
            if (i > 0) {
                line.append(", ");
            }
            line.append(String.format(Locale.ROOT, "%.3f", nanos[i] / 1_000_000.0));
        }
        System.out.println(title + "：");
        System.out.println("  每次耗时（ms）：" + line);
        long[] sorted = Arrays.copyOf(nanos, nanos.length);
        Arrays.sort(sorted);
        System.out.printf(Locale.ROOT, "  中位数：%.3f ms%n", medianNanos(sorted) / 1_000_000.0);
        System.out.printf(Locale.ROOT, "  最小：%.3f ms，最大：%.3f ms%n",
                sorted[0] / 1_000_000.0, sorted[sorted.length - 1] / 1_000_000.0);
    }

    private static double medianNanos(long[] sortedNanos) {
        int middle = sortedNanos.length / 2;
        return sortedNanos.length % 2 == 0
                ? sortedNanos[middle - 1] / 2.0 + sortedNanos[middle] / 2.0
                : sortedNanos[middle];
    }

    private static String displayName(String system, DatasetScale scale) {
        if (system.toLowerCase().contains("file")) {
            return "文件（" + java.nio.file.Path.of(scale.moviesCsv()).getFileName() + "）";
        }
        if (system.toLowerCase().contains("opengauss")) {
            return "openGauss（filmdb）";
        }
        return "PostgreSQL（filmdb_old）";
    }

    private static String[] sortedLines(String text) {
        String[] lines = text.isEmpty() ? new String[0] : text.split("\n");
        Arrays.sort(lines);
        return lines;
    }

    private static void verifyResult(String result, String[] expected) {
        if (!Arrays.equals(sortedLines(result), expected)) {
            throw new IllegalStateException("标题检索结果与同规模 CSV 不一致。");
        }
    }

    private static int countLines(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        int lines = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    // 数据库返回顺序可能不同，先按行排序再取摘要，用于核对三份结果内容一致。
    private static String digest(String text) {
        try {
            String[] lines = sortedLines(text);
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException("计算结果摘要失败。", e);
        }
    }
}
