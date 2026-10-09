import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

// 只准备扩充基线，不测速、不更新姓名、不写入原始表或更新工作表。
// 用法：DatasetBuilder [10x|50x|100x] [refresh]；无参数默认10x。
// 工作目录为 java；重复运行时校验并复用已有正确数据。
public class DatasetBuilder {
    private static final int BATCH_SIZE = 1000;
    private static final long RANDOM_SEED = 20261005L;
    private static final Logger GAUSS_LOGGER = Logger.getLogger("org.opengauss");

    public static void main(String[] args) throws Exception {
        boolean legacyRefresh = args.length == 1 && args[0].equals("refresh");
        DatasetScale scale = args.length == 0 || legacyRefresh
                ? DatasetScale.TENFOLD : DatasetScale.parse(args[0]);
        boolean refresh = legacyRefresh || (args.length == 2 && args[1].equals("refresh"));
        if (args.length > 2 || scale == DatasetScale.ORIGINAL || (args.length == 2 && !refresh)) {
            throw new IllegalArgumentException("用法：DatasetBuilder [10x|50x|100x] [refresh]；原始数据不能重建。");
        }
        GAUSS_LOGGER.setLevel(Level.WARNING);
        Path directory = Path.of("../导出的文件");
        Path movieSource = directory.resolve("movies.csv");
        Path peopleSource = directory.resolve("people.csv");
        byte[] movieHash = fileHash(movieSource);
        byte[] peopleHash = fileHash(peopleSource);

        List<String[]> movieSeed = readCsv(movieSource, 5);
        List<String[]> peopleSeed = readCsv(peopleSource, 6);
        if (movieSeed.size() != 9204 || peopleSeed.size() != 16489) {
            throw new IOException("原始记录数不符，未开始导入数据库。");
        }
        List<String[]> movies = expandRows(movieSeed, false, scale.factor());
        List<String[]> people = expandRows(peopleSeed, true, scale.factor());
        writeCsv(Path.of(scale.moviesCsv()), movies, refresh);
        writeCsv(Path.of(scale.peopleCsv()), people, refresh);
        System.out.println(scale.displayName() + " CSV 已准备：电影 " + movies.size() + " 条，人物 " + people.size() + " 条。");
        System.out.println("非空标题和姓名已加随机后缀及编号；电影种子：" + RANDOM_SEED
                + "，人物种子：" + (RANDOM_SEED + 1));
        System.out.println("标题包含 man：" + movies.stream().filter(row -> row[1].contains("man")).count());
        System.out.println("姓名含大写 T：" + people.stream()
                .filter(row -> row[1].contains("T") || row[2].contains("T")).count());

        prepareDatabase("PostgreSQL", new DatabaseManipulation().createPeopleUpdateConnection(),
                false, movies, people, scale, refresh);
        prepareDatabase("openGauss", new OpenGaussDatabaseManipulation().createPeopleUpdateConnection(),
                true, movies, people, scale, refresh);

        // 关闭导入连接后，用新连接校验实际持久保存的内容。
        verifyDatabase("PostgreSQL", new DatabaseManipulation().createPeopleUpdateConnection(), movies, people, scale);
        verifyDatabase("openGauss", new OpenGaussDatabaseManipulation().createPeopleUpdateConnection(), movies, people, scale);
        if (!Arrays.equals(movieHash, fileHash(movieSource))
                || !Arrays.equals(peopleHash, fileHash(peopleSource))) {
            throw new IOException("原始 CSV 内容发生变化。");
        }
        System.out.println("三端逐字段校验通过；原始 CSV 未变。测速须选择对应的 " + scale.tag() + " 配置。");
    }

    // 固定按原始 ID 排序，编号为 1..N；非空标题、姓名加随机后缀，其余字段保持原值。
    private static List<String[]> expandRows(List<String[]> seed, boolean people, int copies) {
        seed.sort(Comparator.comparingInt(row -> Integer.parseInt(row[0])));
        Random random = new Random(RANDOM_SEED + (people ? 1 : 0));
        List<String[]> expanded = new ArrayList<>(seed.size() * copies);
        for (int copy = 0; copy < copies; copy++) {
            for (String[] row : seed) {
                String[] newRow = row.clone();
                newRow[0] = Integer.toString(expanded.size() + 1);
                // 后缀只含小写十六进制、连字符与数字，不额外引入 man 或大写 T。
                String suffix = " " + Integer.toHexString(random.nextInt(65536)) + "-" + newRow[0];
                if (!newRow[1].isEmpty()) {
                    newRow[1] += suffix;
                }
                if (people && !newRow[2].isEmpty()) {
                    newRow[2] += suffix;
                }
                expanded.add(newRow);
            }
        }
        return expanded;
    }

    private static List<String[]> readCsv(Path file, int columns) throws IOException {
        List<String[]> rows = new ArrayList<>();
        Set<Integer> ids = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] row = parseCsvLine(line, columns);
                int id;
                try {
                    id = Integer.parseInt(row[0]);
                    for (int column : new int[] {3, 4}) {
                        if (!row[column].isEmpty()) {
                            Integer.parseInt(row[column]);
                        }
                    }
                } catch (NumberFormatException e) {
                    throw new IOException("CSV 数字字段无效：" + file, e);
                }
                if (!ids.add(id)) {
                    throw new IOException("CSV ID 重复：" + file + "，ID=" + id);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    // 当前两份导出均无表头、每条记录一行；支持逗号、双引号转义和空字段。
    private static String[] parseCsvLine(String line, int columns) throws IOException {
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
        if (inQuotes || fields.size() != columns) {
            throw new IOException("CSV 字段数或引号不符，要求 " + columns + " 列。");
        }
        return fields.toArray(String[]::new);
    }

    // 普通运行校验并复用；refresh 只替换选定规模的扩充 CSV，原始 CSV 始终只读。
    private static void writeCsv(Path file, List<String[]> expected, boolean refresh) throws IOException {
        if (Files.exists(file) && !refresh) {
            List<String[]> actual = readCsv(file, expected.get(0).length);
            if (actual.size() != expected.size()) {
                throw new IOException("已有扩充 CSV 记录数不符，未覆盖：" + file);
            }
            for (int i = 0; i < expected.size(); i++) {
                if (!Arrays.equals(actual.get(i), expected.get(i))) {
                    throw new IOException("已有扩充 CSV 内容不符，未覆盖：" + file);
                }
            }
            return;
        }
        Path temporary = Files.createTempFile(file.getParent(), "dataset-expanded-", ".csv");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                for (String[] row : expected) {
                    for (int column = 0; column < row.length; column++) {
                        if (column > 0) {
                            writer.write(',');
                        }
                        String field = row[column];
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
            if (refresh) {
                Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temporary, file);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void prepareDatabase(String name, Connection connection, boolean openGauss,
                                        List<String[]> movies, List<String[]> people, DatasetScale scale, boolean refresh)
            throws SQLException {
        try (connection) {
            connection.setAutoCommit(false);
            try {
                if (openGauss) {
                    try (Statement statement = connection.createStatement();
                         ResultSet result = statement.executeQuery("show behavior_compat_options")) {
                        result.next();
                        if (Arrays.stream(result.getString(1).split(","))
                                .noneMatch(option -> option.trim().equalsIgnoreCase("accept_empty_str"))) {
                            throw new SQLException("openGauss 须保留 accept_empty_str，未开始导入。");
                        }
                    }
                }
                System.out.println(name + "：准备电影 " + scale.tag() + " 基线。");
                loadTable(connection, false, movies, scale, refresh);
                System.out.println(name + "：准备人物 " + scale.tag() + " 基线。");
                loadTable(connection, true, people, scale, refresh);
                connection.commit();
                System.out.println(name + "：两张 " + scale.tag() + " 基线表已提交。");
            } catch (SQLException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            }
            // 当前 openGauss 不允许 ANALYZE 位于事务块内；两端统一在提交后执行。
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                statement.execute("analyze " + scale.moviesTable());
                statement.execute("analyze " + scale.peopleTable());
            }
            System.out.println(name + "：" + scale.tag() + " 基线统计信息已更新。");
        }
    }

    // 只写选定规模的两张固定基线表；refresh 重建这些副本，普通运行核对并复用。
    private static void loadTable(Connection connection, boolean people, List<String[]> expected,
                                  DatasetScale scale, boolean refresh)
            throws SQLException {
        String table = people ? scale.peopleTable() : scale.moviesTable();
        String ddl = people ? """
                create table if not exists %s (
                    peopleid integer primary key, first_name text, surname text not null,
                    born integer, died integer, gender varchar(1)
                )
                """ : """
                create table if not exists %s (
                    movieid integer primary key, title text not null, country varchar(2),
                    year_released integer, runtime integer
                )
                """;
        boolean empty;
        try (Statement statement = connection.createStatement()) {
            statement.execute(ddl.formatted(table));
            if (refresh) {
                statement.executeUpdate("truncate table " + table);
            }
            try (ResultSet result = statement.executeQuery("select count(*) from " + table)) {
                result.next();
                empty = result.getInt(1) == 0;
            }
        }
        if (empty) {
            String insert = people ? """
                    insert into %s (peopleid, first_name, surname, born, died, gender)
                    values (?, ?, ?, ?, ?, ?)
                    """ : """
                    insert into %s (movieid, title, country, year_released, runtime)
                    values (?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement statement = connection.prepareStatement(insert.formatted(table))) {
                for (int row = 0; row < expected.size(); row++) {
                    String[] fields = expected.get(row);
                    for (int column = 0; column < fields.length; column++) {
                        String value = fields[column];
                        boolean numeric = column == 0 || column == 3 || column == 4;
                        if (value.isEmpty() && emptyMeansNull(people, column)) {
                            statement.setNull(column + 1, numeric ? Types.INTEGER : Types.VARCHAR);
                        } else if (numeric) {
                            statement.setInt(column + 1, Integer.parseInt(value));
                        } else {
                            statement.setString(column + 1, value);
                        }
                    }
                    statement.addBatch();
                    if ((row + 1) % BATCH_SIZE == 0 || row + 1 == expected.size()) {
                        statement.executeBatch();
                        statement.clearBatch();
                    }
                    if ((row + 1) % 50000 == 0) {
                        System.out.println("  " + table + " 已写入 " + (row + 1) + " 条。");
                    }
                }
            }
        }
        verifyTable(connection, people, expected, scale);
    }

    // 按当前导出的字段语义解释空字段，surname 的空字符串不变成 NULL。
    private static boolean emptyMeansNull(boolean people, int column) {
        return column == 4 || (people && (column == 1 || column == 3));
    }

    private static void verifyDatabase(String name, Connection connection,
                                       List<String[]> movies, List<String[]> people, DatasetScale scale) throws SQLException {
        try (connection) {
            verifyTable(connection, false, movies, scale);
            verifyTable(connection, true, people, scale);
        }
        System.out.println(name + "：新连接逐字段校验通过，电影 " + movies.size()
                + " 条、人物 " + people.size() + " 条。");
    }

    private static void verifyTable(Connection connection, boolean people, List<String[]> expected, DatasetScale scale)
            throws SQLException {
        String sql = people ? """
                select peopleid, first_name, surname, born, died, gender
                from %s order by peopleid
                """ : """
                select movieid, title, country, year_released, runtime
                from %s order by movieid
                """;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql.formatted(people ? scale.peopleTable() : scale.moviesTable()))) {
            int row = 0;
            while (result.next()) {
                if (row >= expected.size()) {
                    throw new SQLException("扩充表存在多余记录，未覆盖已有表。");
                }
                String[] fields = expected.get(row);
                for (int column = 0; column < fields.length; column++) {
                    String value = fields[column];
                    if (value.isEmpty() && emptyMeansNull(people, column)) {
                        value = null;
                    }
                    if (!java.util.Objects.equals(value, result.getString(column + 1))) {
                        throw new SQLException("扩充表内容不符：ID=" + fields[0]
                                + "，列=" + (column + 1) + "；未覆盖已有表。");
                    }
                }
                row++;
            }
            if (row != expected.size()) {
                throw new SQLException("扩充表记录数不符，未覆盖已有表。");
            }
        }
    }

    private static byte[] fileHash(Path file) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
    }
}
