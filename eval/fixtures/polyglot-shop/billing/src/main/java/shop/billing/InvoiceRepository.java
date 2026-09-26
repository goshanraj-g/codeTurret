package shop.billing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class InvoiceRepository {

    private final JdbcTemplate jdbc;

    public InvoiceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Invoice> findByCustomer(String customer) {
        String sql = "SELECT id, customer, amount_cents FROM invoices WHERE customer = '" + customer + "'";
        return jdbc.query(sql, (rs, i) -> new Invoice(rs.getLong(1), rs.getString(2), rs.getLong(3)));
    }
}
