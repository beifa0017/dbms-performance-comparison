import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

// 只更新工作副本；选定规模的 CSV 基线仅用于恢复和校验。
public class PeopleFileUpdater implements PeopleUpdater {
    private final Path sourceFile;
    private final Path workFile;
    private final List<List<String>> baseline;

    public PeopleFileUpdater(Path sourceFile, Path workFile) throws IOException {
        this.sourceFile = sourceFile.toRealPath();
        this.workFile = workFile.toAbsolutePath().normalize();
        if (this.sourceFile.equals(this.workFile)
                || (Files.exists(this.workFile) && Files.isSameFile(this.sourceFile, this.workFile))) {
            throw new IOException("工作副本不能是原始 people.csv。");
        }
        baseline = readRows(this.sourceFile);
    }

    public Path getWorkFile() {
        return workFile;
    }

    public int getRowCount() {
        return baseline.size();
    }

    // 每轮（包括预热）在计时外恢复，避免反复把 Ttt 替换成 Ttttt。
    public void reset() throws IOException {
        Files.createDirectories(workFile.getParent());
        Files.copy(sourceFile, workFile, StandardCopyOption.REPLACE_EXISTING);
    }

    // 计时包含打开、解析、姓名替换、完整写入、关闭和替换工作文件。
    public int updateNames() throws IOException {
        Path temporary = Files.createTempFile(workFile.getParent(), "people-update-", ".csv");
        int changedRows = 0;
        try {
            try (BufferedReader reader = Files.newBufferedReader(workFile, StandardCharsets.UTF_8);
                 BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    List<String> fields = parseCsvLine(line);
                    String firstName = fields.get(1).replace("T", "Ttt");
                    String surname = fields.get(2).replace("T", "Ttt");
                    if (!firstName.equals(fields.get(1)) || !surname.equals(fields.get(2))) {
                        changedRows++;
                    }
                    fields.set(1, firstName);
                    fields.set(2, surname);
                    writeRow(writer, fields);
                }
            }
            try {
                Files.move(temporary, workFile,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, workFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return changedRows;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    // 在计时外逐字段校验：两列姓名符合预期，其余四列、行数和顺序不变。
    public int verifyResult() throws IOException {
        List<List<String>> actual = readRows(workFile);
        if (actual.size() != baseline.size()) {
            throw new IOException("更新后记录数发生变化。");
        }
        int changedRows = 0;
        for (int row = 0; row < baseline.size(); row++) {
            List<String> before = baseline.get(row);
            if (before.get(1).contains("T") || before.get(2).contains("T")) {
                changedRows++;
            }
            for (int column = 0; column < 6; column++) {
                String expected = before.get(column);
                if (column == 1 || column == 2) {
                    expected = expected.replace("T", "Ttt");
                }
                if (!expected.equals(actual.get(row).get(column))) {
                    throw new IOException("更新结果不符：第 " + (row + 1) + " 行，第 " + (column + 1) + " 列。");
                }
            }
        }
        return changedRows;
    }

    private static List<List<String>> readRows(Path file) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                rows.add(parseCsvLine(line));
            }
        }
        return rows;
    }

    // 当前导出无表头、每条记录一行，六列为 id、名字、姓氏、出生、死亡、性别。
    // 空字段不转成 null：first_name 的空字段代表原 NULL，surname 的空字段代表空字符串。
    private static List<String> parseCsvLine(String line) throws IOException {
        if (line.startsWith("\uFEFF")) {
            line = line.substring(1);
        }
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
        fields.add(field.toString());
        if (inQuotes || fields.size() != 6) {
            throw new IOException("人物 CSV 应每行有 6 个字段，且引号闭合。");
        }
        return fields;
    }

    private static void writeRow(BufferedWriter writer, List<String> fields) throws IOException {
        for (int column = 0; column < fields.size(); column++) {
            if (column > 0) {
                writer.write(',');
            }
            String field = fields.get(column);
            if (field.contains(",") || field.contains("\"")) {
                writer.write('"');
                writer.write(field.replace("\"", "\"\""));
                writer.write('"');
            } else {
                writer.write(field);
            }
        }
        writer.newLine();
    }
}
