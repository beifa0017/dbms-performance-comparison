import java.sql.DriverManager;
public class DriverCheck {
    public static void main(String[] args) throws Exception {
        Class.forName("org.postgresql.Driver");
        Class.forName("org.opengauss.Driver");
        System.out.println(DriverManager.getDriver("jdbc:postgresql://localhost:11451/filmdb_old").getClass().getName());
        System.out.println(DriverManager.getDriver("jdbc:opengauss://localhost:26000/filmdb").getClass().getName());
    }
}