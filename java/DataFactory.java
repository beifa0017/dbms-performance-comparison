import java.util.Locale;

public class DataFactory {
    // 规模只由固定枚举选择，文件路径和表名不能由任意输入替换。
    public DataManipulation createDataManipulation(String arg, DatasetScale scale) {
        return switch (arg.toLowerCase(Locale.ROOT)) {
            case "file" -> new FileManipulation(scale);
            case "database", "postgresql" -> new DatabaseManipulation(scale);
            case "opengauss" -> new OpenGaussDatabaseManipulation(scale);
            default -> throw new IllegalArgumentException("参数应为 file、postgresql（或 database）、opengauss。");
        };
    }
}
