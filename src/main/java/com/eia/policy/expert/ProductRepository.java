package com.eia.policy.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the product catalog. Every read is tenant-scoped, so a
 * product id from another tenant is indistinguishable from a missing one.
 *
 * <p>The optional {@code q} filter is a case-insensitive substring match over
 * the product code and name; the optional {@code status} filter is an exact
 * match. Ordering is stable and catalog-friendly: product code first, then id
 * as the tie-breaker.</p>
 */
@Repository
public class ProductRepository {
    private static final String SELECT_COLUMNS = """
            SELECT id, tenant_id, product_code, product_name, product_line, jurisdiction,
                   status, version, created_at, updated_at
              FROM product
            """;

    private static final RowMapper<Product> MAPPER = (rs, rowNum) -> new Product(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("product_code"),
            rs.getString("product_name"),
            rs.getString("product_line"),
            rs.getString("jurisdiction"),
            rs.getString("status"),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public ProductRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Product> findById(UUID tenantId, UUID id) {
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE tenant_id = ? AND id = ?
                """, MAPPER, tenantId, id).stream().findFirst();
    }

    public Optional<Product> findByCode(UUID tenantId, String productCode) {
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE tenant_id = ? AND product_code = ?
                """, MAPPER, tenantId, productCode).stream().findFirst();
    }

    public List<Product> findAll(UUID tenantId, String q, String status) {
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS)
                .append(" WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (q != null) {
            sql.append("""
                     AND (LOWER(product_code) LIKE ?
                       OR LOWER(product_name) LIKE ?)
                    """);
            String pattern = "%" + q.toLowerCase(Locale.ROOT) + "%";
            args.add(pattern);
            args.add(pattern);
        }
        sql.append(" ORDER BY product_code ASC, id ASC");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public void insert(Product product) {
        jdbc.update("""
                INSERT INTO product
                       (id, tenant_id, product_code, product_name, product_line, jurisdiction,
                        status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, product.id(), product.tenantId(), product.productCode(),
                product.productName(), product.productLine(), product.jurisdiction(),
                product.status(), product.version(), Timestamp.from(product.createdAt()),
                Timestamp.from(product.updatedAt()));
    }

    /**
     * Seeds one row without failing on an existing {@code (tenant, product_code)}
     * pair. Used only by the demo seeder, so a restart never duplicates or
     * overwrites an operator's product edits.
     */
    public int insertIfAbsent(Product product) {
        return jdbc.update("""
                INSERT INTO product
                       (id, tenant_id, product_code, product_name, product_line, jurisdiction,
                        status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, product.id(), product.tenantId(), product.productCode(),
                product.productName(), product.productLine(), product.jurisdiction(),
                product.status(), product.version(), Timestamp.from(product.createdAt()),
                Timestamp.from(product.updatedAt()));
    }

    /**
     * Advances the status once. The version and expected current status are part
     * of the predicate, so a concurrent transition affects no row instead of
     * overwriting the winner.
     */
    public int updateStatus(UUID tenantId, UUID id, String targetStatus, String expectedStatus,
                            int expectedVersion, Timestamp updatedAt) {
        return jdbc.update("""
                UPDATE product
                   SET status = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND id = ? AND version = ? AND status = ?
                """, targetStatus, updatedAt, tenantId, id, expectedVersion, expectedStatus);
    }
}
