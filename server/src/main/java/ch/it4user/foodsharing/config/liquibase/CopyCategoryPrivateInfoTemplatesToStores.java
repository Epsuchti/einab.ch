package ch.it4user.foodsharing.config.liquibase;

import ch.it4user.foodsharing.domain.entity.PrivateInfoTemplate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import liquibase.change.custom.CustomTaskChange;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomChangeException;
import liquibase.exception.ValidationErrors;
import liquibase.resource.ResourceAccessor;

/**
 * Private info templates moved from "per teacher and category" to "per teacher and store".
 * For every store a teacher has given EinAbs at, this copies the teacher's category template into a store template.
 * When a teacher used one store under several categories, the category of the most recent EinAb there wins
 * (among the categories the teacher has a template for). The category templates themselves stay as the fallback.
 */
public class CopyCategoryPrivateInfoTemplatesToStores implements CustomTaskChange {

    private int copied;

    @Override
    public void execute(Database database) throws CustomChangeException {
        Connection connection = ((JdbcConnection) database.getConnection()).getUnderlyingConnection();
        try {
            Map<String, String> categoryTemplates = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT teacher_id, category, content FROM private_info_templates WHERE store_name IS NULL");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    categoryTemplates.put(rows.getString(1) + "|" + rows.getString(2), rows.getString(3));
                }
            }
            if (categoryTemplates.isEmpty()) {
                return;
            }
            Set<String> doneStores = new HashSet<>();
            Timestamp now = Timestamp.from(Instant.now());
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT teacher_id, category, business_name FROM einabs "
                            + "WHERE business_name IS NOT NULL ORDER BY start_date_time DESC");
                 PreparedStatement insert = connection.prepareStatement(
                         "INSERT INTO private_info_templates "
                                 + "(id, version, created_at, updated_at, teacher_id, category, store_name, store_key, content) "
                                 + "VALUES (?, 0, ?, ?, ?, ?, ?, ?, ?)");
                 ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    String teacherId = rows.getString(1);
                    String category = rows.getString(2);
                    String storeName = rows.getString(3).trim().replaceAll("\\s+", " ");
                    String storeKey = PrivateInfoTemplate.storeKeyFor(storeName);
                    String content = categoryTemplates.get(teacherId + "|" + category);
                    if (storeKey.isEmpty() || content == null || !doneStores.add(teacherId + "|" + storeKey)) {
                        continue;
                    }
                    insert.setObject(1, UUID.randomUUID());
                    insert.setTimestamp(2, now);
                    insert.setTimestamp(3, now);
                    insert.setObject(4, UUID.fromString(teacherId));
                    insert.setString(5, category);
                    insert.setString(6, storeName);
                    insert.setString(7, storeKey);
                    insert.setString(8, content);
                    insert.executeUpdate();
                    copied++;
                }
            }
        } catch (Exception e) {
            throw new CustomChangeException("Copying private info templates to stores failed", e);
        }
    }

    @Override
    public String getConfirmationMessage() {
        return "Copied " + copied + " category private info templates to store templates";
    }

    @Override
    public void setUp() {
    }

    @Override
    public void setFileOpener(ResourceAccessor resourceAccessor) {
    }

    @Override
    public ValidationErrors validate(Database database) {
        return new ValidationErrors();
    }
}
