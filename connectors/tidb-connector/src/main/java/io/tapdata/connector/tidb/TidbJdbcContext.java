package io.tapdata.connector.tidb;

import io.tapdata.common.CommonDbConfig;
import io.tapdata.common.JdbcContext;
import io.tapdata.kit.EmptyKit;
import io.tapdata.kit.StringKit;

import java.sql.SQLException;
import java.text.DecimalFormat;
import java.time.ZoneId;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TidbJdbcContext extends JdbcContext {

    public TidbJdbcContext(CommonDbConfig config) {
        super(config);
    }

    @Override
    public String queryVersion() throws SQLException {
        AtomicReference<String> version = new AtomicReference<>();
        queryWithNext(TIDB_VERSION, resultSet -> version.set(resultSet.getString(1)));
        return version.get();
    }

    public TimeZone queryTimeZone() throws SQLException {
        AtomicReference<String> timezone = new AtomicReference<>();
        queryWithNext(TIDB_TIMEZONE, resultSet -> timezone.set(resultSet.getString("Value")));
        String reg = "[+\\-](\\d+):00";
        Pattern pattern = Pattern.compile(reg);
        Matcher matcher = pattern.matcher(timezone.get());
        DecimalFormat decimalFormat = new DecimalFormat("00");
        if (matcher.find()) {
            int hour = Integer.parseInt(matcher.group(1));
            ZoneId zoneId = ZoneId.of(timezone.get().replaceAll(matcher.group(1), decimalFormat.format(hour)));
            return TimeZone.getTimeZone(zoneId);
        } else {
            return TimeZone.getDefault();
        }
    }

    @Override
    protected String queryAllTablesSql(String schema, List<String> tableNames) {
        String tableSql = EmptyKit.isNotEmpty(tableNames) ? "AND TABLE_NAME IN (" + StringKit.joinString(tableNames, "'", ",") + ")" : "";
        return String.format(TIDB_ALL_TABLE, schema, tableSql);
    }

    @Override
    protected String queryAllColumnsSql(String schema, List<String> tableNames) {
        String tableSql = EmptyKit.isNotEmpty(tableNames) ? "AND TABLE_NAME IN (" + StringKit.joinString(tableNames, "'", ",") + ")" : "";
        return String.format(TIDB_ALL_COLUMN, schema, tableSql);
    }

    @Override
    protected String queryAllIndexesSql(String schema, List<String> tableNames) {
        String tableSql = EmptyKit.isNotEmpty(tableNames) ? "AND TABLE_NAME IN (" + StringKit.joinString(tableNames, "'", ",") + ")" : "";
        return String.format(TIDB_ALL_INDEX, schema, tableSql);
    }

    private static final String TIDB_ALL_TABLE =
            "SELECT\n" +
                    "\tTABLE_NAME `tableName`,\n" +
                    "\tTABLE_COMMENT `tableComment`\n" +
                    "FROM\n" +
                    "\tINFORMATION_SCHEMA.TABLES\n" +
                    "WHERE\n" +
                    "\tTABLE_SCHEMA = '%s' %s\n" +
                    "\tAND TABLE_TYPE = 'BASE TABLE'";

    private static final String TIDB_ALL_COLUMN =
            "SELECT TABLE_NAME `tableName`,\n" +
                    "       COLUMN_NAME `columnName`,\n" +
                    "       COLUMN_TYPE `dataType`,\n" +
                    "       IS_NULLABLE `nullable`,\n" +
                    "       COLUMN_COMMENT `columnComment`\n" +
                    "FROM INFORMATION_SCHEMA.COLUMNS\n" +
                    "WHERE TABLE_SCHEMA = '%s' %s\n" +
                    "ORDER BY ORDINAL_POSITION";

    private final static String TIDB_ALL_INDEX =
            "SELECT\n" +
                    "\tTABLE_NAME `tableName`,\n" +
                    "\tINDEX_NAME `indexName`,\n" +
                    "\t(CASE\n" +
                    "\t\tWHEN COLLATION = 'A' THEN 1\n" +
                    "\t\tELSE 0\n" +
                    "\tEND) `isAsc`,\n" +
                    "\t(CASE\n" +
                    "\t\tWHEN NON_UNIQUE = 0 THEN 1\n" +
                    "\t\tELSE 0\n" +
                    "\tEND) `isUnique`,\n" +
                    "\t(CASE\n" +
                    "\t\tWHEN INDEX_NAME = 'PRIMARY' THEN 1\n" +
                    "\t\tELSE 0\n" +
                    "\tEND) `isPk`,\n" +
                    "\tCOLUMN_NAME `columnName`\n" +
                    "FROM\n" +
                    "\tINFORMATION_SCHEMA.STATISTICS\n" +
                    "WHERE\n" +
                    "\tTABLE_SCHEMA = '%s' %s\n" +
                    "ORDER BY\n" +
                    "\tINDEX_NAME,\n" +
                    "\tSEQ_IN_INDEX;";

    private final static String TIDB_VERSION = "SELECT @@VERSION";

    private final static String TIDB_TIMEZONE = "SHOW VARIABLES LIKE 'time_zone'";

}
