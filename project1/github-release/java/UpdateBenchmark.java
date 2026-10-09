import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

// 姓名更新独立入口：UpdateBenchmark [file|postgresql|opengauss] [1x|10x|50x|100x]，默认 file、1x。
// 运行工作目录为 project1/java；不影响标题检索 Benchmark。
public class UpdateBenchmark {
    private static final int WARMUP_RUNS = 3;
    private static final int MEASURED_RUNS = 20;
    private static final Logger OPEN_GAUSS_LOGGER = Logger.getLogger("org.opengauss");

    public static void main(String[] args) throws Exception {
        String system = args.length == 0 ? "file" : args[0].toLowerCase(Locale.ROOT);
        if (system.equals("database")) {
            system = "postgresql";
        }
        if (args.length > 2) {
            throw new IllegalArgumentException("用法：UpdateBenchmark file|postgresql|opengauss [规模]。");
        }
        DatasetScale scale = args.length > 1 ? DatasetScale.parse(args[1]) : DatasetScale.ORIGINAL;
        int expectedChangedRows = 1287 * scale.factor();
        OPEN_GAUSS_LOGGER.setLevel(Level.WARNING);
        try (PeopleUpdater updater = createUpdater(system, scale)) {
            if (updater.getRowCount() != 16489 * scale.factor()) {
                throw new IllegalStateException("人物基线记录数与选定规模不符。");
            }
            boolean file = updater instanceof PeopleFileUpdater;
            String name = file ? "文件（" + Path.of(scale.peopleCsv()).getFileName() + " 的独立工作副本）"
                    : system.equals("postgresql") ? "PostgreSQL（filmdb_old）" : "openGauss（filmdb）";
            String timing = file ? "完整操作（打开、解析、替换姓名、写入、关闭、替换工作文件）"
                    : "更新操作（连接已建立，创建语句、执行 update、commit、关闭语句）";
            System.out.println("系统：" + name);
            System.out.println("数据规模：" + scale.displayName());
            System.out.println("实验：first_name 和 surname 中的大写 T 替换为 Ttt");
            System.out.println("工作副本：" + (file ? ((PeopleFileUpdater) updater).getWorkFile()
                    : scale.peopleWorkTable() + "；" + scale.peopleTable() + " 仅用作只读基线"));
            System.out.println("预热 " + WARMUP_RUNS + " 次，正式测量 " + MEASURED_RUNS + " 次");
            System.out.println("每轮在计时外恢复基线、校验和打印。");
            System.out.println(file ? "文件未强制同步到磁盘；关闭和替换不等同于数据库持久提交。"
                    : "计时包含 update 和 commit；连接建立、建表、恢复和校验不计时；synchronous_commit=on。");

            for (int i = 0; i < WARMUP_RUNS; i++) {
                runOnce(updater, expectedChangedRows);
            }
            long[] nanos = new long[MEASURED_RUNS];
            for (int i = 0; i < MEASURED_RUNS; i++) {
                nanos[i] = runOnce(updater, expectedChangedRows);
            }

            printTimes(timing, nanos);
            System.out.println("总记录数：" + updater.getRowCount());
            System.out.println("实际改变记录数：" + updater.verifyResult());
            System.out.println("每轮校验通过：姓名符合预期，其余字段、空字段和记录数不变。");
        }
    }

    private static PeopleUpdater createUpdater(String system, DatasetScale scale) throws Exception {
        return switch (system) {
            case "file" -> new PeopleFileUpdater(
                    Path.of(scale.peopleCsv()), Path.of(scale.peopleWorkCsv()));
            case "postgresql" -> new PeopleDatabaseUpdater(
                    new DatabaseManipulation().createPeopleUpdateConnection(), false, scale);
            case "opengauss" -> new PeopleDatabaseUpdater(
                    new OpenGaussDatabaseManipulation().createPeopleUpdateConnection(), true, scale);
            default -> throw new IllegalArgumentException("参数应为 file、postgresql 或 opengauss。");
        };
    }

    private static long runOnce(PeopleUpdater updater, int expectedChangedRows) throws Exception {
        updater.reset();
        long start = System.nanoTime();
        int changedRows = updater.updateNames();
        long elapsed = System.nanoTime() - start;
        if (changedRows != updater.verifyResult() || changedRows != expectedChangedRows) {
            throw new IllegalStateException("改变记录数与校验结果或选定规模不一致。");
        }
        return elapsed;
    }

    private static void printTimes(String title, long[] nanos) {
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
        double median = sorted.length % 2 == 0
                ? sorted[middle - 1] / 2.0 + sorted[middle] / 2.0
                : sorted[middle];
        System.out.println(title + "：");
        System.out.println("  每次耗时（ms）：" + times);
        System.out.printf(Locale.ROOT, "  中位数：%.3f ms%n", median / 1_000_000.0);
        System.out.printf(Locale.ROOT, "  最小：%.3f ms，最大：%.3f ms%n",
                sorted[0] / 1_000_000.0, sorted[sorted.length - 1] / 1_000_000.0);
    }
}
