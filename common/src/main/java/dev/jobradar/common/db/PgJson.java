package dev.jobradar.common.db;

import java.sql.SQLException;
import org.postgresql.util.PGobject;

public final class PgJson {

    private PgJson() {
    }

    public static PGobject jsonb(String json) {
        PGobject pgObject = new PGobject();
        pgObject.setType("jsonb");
        try {
            pgObject.setValue(json);
        } catch (SQLException e) {
            throw new IllegalStateException("Invalid JSON for jsonb column", e);
        }
        return pgObject;
    }
}
