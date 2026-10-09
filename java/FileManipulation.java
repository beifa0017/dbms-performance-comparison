import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class FileManipulation implements DataManipulation {
    // 从 project1/java 出发，读取你导出的 CSV。
    private final String moviesFile;
    private int scannedMovieCount;

    // 仅选择固定规模的基线；无参数构造仍读取原规模。
    public FileManipulation(DatasetScale scale) {
        moviesFile = scale.moviesCsv();
    }

    public String getMoviesFile() {
        return Paths.get(moviesFile).toAbsolutePath().normalize().toString();
    }

    public int getScannedMovieCount() {
        return scannedMovieCount;
    }

    @Override
    public String findMoviesByTitle(String title) {
        scannedMovieCount = 0;
        StringBuilder result = new StringBuilder();

        try (BufferedReader reader = Files.newBufferedReader(
                Paths.get(moviesFile), StandardCharsets.UTF_8)) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (firstLine && line.startsWith("\uFEFF")) {
                    line = line.substring(1);
                }
                List<String> fields = parseCsvLine(line);
                if (firstLine && fields.get(0).equalsIgnoreCase("movieid")) {
                    firstLine = false;
                    continue;
                }
                firstLine = false;
                if (fields.size() != 5) {
                    throw new IOException("CSV 每条电影记录应有 5 个字段。");
                }
                scannedMovieCount++;
                // 五列依次为 movieid、title、country、year_released、runtime。
                if (fields.get(1).contains(title)) {
                    result.append(fields.get(0)).append("\t")
                            .append(fields.get(1)).append("\t")
                            .append(fields.get(2)).append("\t")
                            .append(fields.get(3)).append("\t")
                            .append(fields.get(4)).append("\n");
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取 movies.csv 失败：" + getMoviesFile(), e);
        }
        return result.toString();
    }

    // CSV 的片名可能含逗号或双引号，不能直接使用 split(",")。
    // 当前导出文件每条记录占一行；引号内换行的记录会明确报告错误。
    private List<String> parseCsvLine(String line) throws IOException {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char character = line.charAt(i);
            if (character == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (character == ',' && !inQuotes) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(character);
            }
        }
        if (inQuotes) {
            throw new IOException("CSV 引号没有闭合，当前读取方式要求每条记录占一行。");
        }
        fields.add(field.toString());
        return fields;
    }

    @Override
    public String findMovieById(int id) {
        scannedMovieCount = 0;
        // 按编号逐行查找，命中后停止；不存在的编号须读到末尾。
        try (BufferedReader reader = Files.newBufferedReader(
                Paths.get(moviesFile), StandardCharsets.UTF_8)) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (firstLine && line.startsWith("\uFEFF")) {
                    line = line.substring(1);
                }
                List<String> fields = parseCsvLine(line);
                if (firstLine && fields.get(0).equalsIgnoreCase("movieid")) {
                    firstLine = false;
                    continue;
                }
                firstLine = false;
                if (fields.size() != 5) {
                    throw new IOException("CSV 每条电影记录应有 5 个字段。");
                }
                scannedMovieCount++;
                if (Integer.parseInt(fields.get(0)) == id) {
                    return String.join("\t", fields) + "\n";
                }
            }
        } catch (IOException | NumberFormatException e) {
            throw new IllegalStateException("电影编号查找失败：" + getMoviesFile(), e);
        }
        return "";
    }
}
