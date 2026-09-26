package shop.billing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CustomerRepository {

    private final JdbcTemplate jdbc;

    public CustomerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Customer findById(long id) {
        return jdbc.queryForObject(
            "SELECT id, name, email FROM customers WHERE id = ?",
            (rs, i) -> new Customer(rs.getLong(1), rs.getString(2), rs.getString(3)),
            id);
    }
}
