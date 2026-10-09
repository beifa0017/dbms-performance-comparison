import java.util.Locale;

// 集中选择固定规模和对应副本，避免文件与数据库误用不同规模。
public enum DatasetScale {
    ORIGINAL(1), TENFOLD(10), FIFTYFOLD(50), HUNDREDFOLD(100);

    private final int factor;

    DatasetScale(int factor) {
        this.factor = factor;
    }

    public static DatasetScale parse(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "1x" -> ORIGINAL;
            case "10x" -> TENFOLD;
            case "50x" -> FIFTYFOLD;
            case "100x" -> HUNDREDFOLD;
            default -> throw new IllegalArgumentException("规模应为 1x、10x、50x 或 100x。");
        };
    }

    public int factor() {
        return factor;
    }

    public String tag() {
        return factor + "x";
    }

    public String displayName() {
        return this == ORIGINAL ? "原规模（1x）" : factor + "倍（" + tag() + "）";
    }

    public String moviesCsv() {
        return "../导出的文件/movies" + (this == ORIGINAL ? "" : "-" + tag()) + ".csv";
    }

    public String peopleCsv() {
        return "../导出的文件/people" + (this == ORIGINAL ? "" : "-" + tag()) + ".csv";
    }

    public String peopleWorkCsv() {
        return "out/people-update" + (this == ORIGINAL ? "" : "-" + tag()) + "/people-work.csv";
    }

    public String moviesTable() {
        return "public.movies" + (this == ORIGINAL ? "" : "_" + tag());
    }

    public String peopleTable() {
        return "public.people" + (this == ORIGINAL ? "" : "_" + tag());
    }

    public String peopleWorkTable() {
        return "public.people_bench" + (this == ORIGINAL ? "" : "_" + tag());
    }
}
