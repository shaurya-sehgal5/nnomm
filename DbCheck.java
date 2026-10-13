import java.sql.*;
class DbCheck {
    public static void main(String[] args) {
        try (Connection c = DriverManager.getConnection(
            "jdbc:postgresql://127.0.0.1:5434/nnomm?options=-c%20TimeZone%3DUTC", "nnomm", "NnommTest2026_X7")) {
            System.out.println("JDBC CONNECTION SUCCESS");
        } catch (Exception e) {
            System.out.println("JDBC CONNECTION FAILED: " + e.getMessage());
        }
    }
}



