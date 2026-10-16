package com.learnhub.assistant.runtime;

import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** 使用原 DataSource；不改变事务绑定，也不影响非助理请求。 */
public final class DeadlineJdbcTemplate extends JdbcTemplate {
    public DeadlineJdbcTemplate(DataSource dataSource) { super(dataSource); }

    @Override protected void applyStatementSettings(Statement statement) throws SQLException {
        super.applyStatementSettings(statement);
        var scope = AssistantIoScope.current();
        if (scope != null) scope.track(statement);
    }
}
