package io.tapdata.connector.tencent.db.mysql;

import io.tapdata.common.CommonDbConfig;
import io.tapdata.common.SqlExecuteCommandFunction;
import io.tapdata.common.ddl.DDLSqlMaker;
import io.tapdata.connector.mysql.*;
import io.tapdata.connector.mysql.entity.MysqlSnapshotOffset;
import io.tapdata.connector.mysql.writer.MysqlWriter;
import io.tapdata.entity.codec.TapCodecsRegistry;
import io.tapdata.entity.event.TapEvent;
import io.tapdata.entity.event.ddl.index.TapCreateIndexEvent;
import io.tapdata.entity.event.ddl.table.TapClearTableEvent;
import io.tapdata.entity.event.ddl.table.TapCreateTableEvent;
import io.tapdata.entity.event.ddl.table.TapDropTableEvent;
import io.tapdata.entity.event.ddl.table.TapFieldBaseEvent;
import io.tapdata.entity.event.dml.TapRecordEvent;
import io.tapdata.entity.logger.TapLogger;
import io.tapdata.entity.schema.TapIndex;
import io.tapdata.entity.schema.TapTable;
import io.tapdata.entity.schema.value.*;
import io.tapdata.entity.simplify.TapSimplify;
import io.tapdata.entity.simplify.pretty.BiClassHandlers;
import io.tapdata.entity.utils.DataMap;
import io.tapdata.kit.DbKit;
import io.tapdata.partition.DatabaseReadPartitionSplitter;
import io.tapdata.pdk.apis.annotations.TapConnectorClass;
import io.tapdata.pdk.apis.context.TapConnectionContext;
import io.tapdata.pdk.apis.context.TapConnectorContext;
import io.tapdata.pdk.apis.entity.*;
import io.tapdata.pdk.apis.functions.ConnectorFunctions;
import io.tapdata.pdk.apis.functions.PDKMethod;
import io.tapdata.pdk.apis.functions.connection.RetryOptions;
import io.tapdata.pdk.apis.functions.connector.source.GetReadPartitionOptions;
import io.tapdata.pdk.apis.functions.connector.target.CreateTableOptions;
import io.tapdata.pdk.apis.partition.FieldMinMaxValue;
import io.tapdata.pdk.apis.partition.TapPartitionFilter;
import io.tapdata.pdk.apis.partition.splitter.StringCaseInsensitiveSplitter;
import io.tapdata.pdk.apis.partition.splitter.TypeSplitterMap;
import org.apache.commons.collections4.CollectionUtils;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * @author jackin
 * @Description
 * @create 2022-12-15 11:42
 **/
@TapConnectorClass("tencent-db-mysql-spec.json")
public class TencentDBMySQLConnector extends MysqlConnector {
	private TapConnectionContext tapConnectionContext;
	private static final String TAG = TencentDBMySQLConnector.class.getSimpleName();
	private static final int MAX_FILTER_RESULT_SIZE = 100;

	private MysqlJdbcContext mysqlJdbcContext;
	private MysqlReader mysqlReader;
	private MysqlWriter mysqlWriter;
	private String version;
	private String connectionTimezone;
	private BiClassHandlers<TapFieldBaseEvent, TapConnectorContext, List<String>> fieldDDLHandlers;
	private DDLSqlMaker ddlSqlMaker;

	@Override
	public void onStart(TapConnectionContext tapConnectionContext) throws Throwable {
		this.tapConnectionContext=tapConnectionContext;
		tapConnectionContext.getConnectionConfig().put("protocolType", "mysql");
		super.onStart(tapConnectionContext);
	}

	@Override
	public ConnectionOptions connectionTest(TapConnectionContext databaseContext, Consumer<TestItem> consumer) {

		databaseContext.getConnectionConfig().put("protocolType", "mysql");
		ConnectionOptions connectionOptions = ConnectionOptions.create();
		CommonDbConfig commonDbConfig = new CommonDbConfig();
		commonDbConfig.set__connectionType(databaseContext.getConnectionConfig().getString("__connectionType"));
		try (
				TencentDBMySQLConnectorTest tencentDBMySQLConnectorTest = new TencentDBMySQLConnectorTest(new MysqlJdbcContext(databaseContext),
						databaseContext, consumer, commonDbConfig, connectionOptions)
		) {
			tencentDBMySQLConnectorTest.testOneByOne();
			return connectionOptions;
		}
	}
	@Override
	public void registerCapabilities(ConnectorFunctions connectorFunctions, TapCodecsRegistry codecRegistry) {
		codecRegistry.registerFromTapValue(TapMapValue.class, "json", tapValue -> toJson(tapValue.getValue()));
		codecRegistry.registerFromTapValue(TapArrayValue.class, "json", tapValue -> toJson(tapValue.getValue()));

		codecRegistry.registerFromTapValue(TapDateTimeValue.class, tapDateTimeValue -> {
			if (tapDateTimeValue.getValue() != null && tapDateTimeValue.getValue().getTimeZone() == null) {
				tapDateTimeValue.getValue().setTimeZone(TimeZone.getTimeZone(this.connectionTimezone));
			}
			return formatTapDateTime(tapDateTimeValue.getValue(), "yyyy-MM-dd HH:mm:ss.SSSSSS");
		});
		codecRegistry.registerFromTapValue(TapDateValue.class, tapDateValue -> {
			if (tapDateValue.getValue() != null && tapDateValue.getValue().getTimeZone() == null) {
				tapDateValue.getValue().setTimeZone(TimeZone.getTimeZone(this.connectionTimezone));
			}
			return formatTapDateTime(tapDateValue.getValue(), "yyyy-MM-dd");
		});
		codecRegistry.registerFromTapValue(TapTimeValue.class, tapTimeValue -> tapTimeValue.getValue().toTimeStr());
		codecRegistry.registerFromTapValue(TapYearValue.class, tapYearValue -> {
			if (tapYearValue.getValue() != null && tapYearValue.getValue().getTimeZone() == null) {
				tapYearValue.getValue().setTimeZone(TimeZone.getTimeZone(this.connectionTimezone));
			}
			return formatTapDateTime(tapYearValue.getValue(), "yyyy");
		});

		codecRegistry.registerFromTapValue(TapBooleanValue.class, "tinyint(1)", TapValue::getValue);

		connectorFunctions.supportCreateTableV2(this::createTableV2);
		connectorFunctions.supportDropTable(this::dropTable);
		connectorFunctions.supportClearTable(this::clearTable);
		connectorFunctions.supportBatchCount(this::batchCount);
		connectorFunctions.supportBatchRead(this::batchRead);
		//云上购买的TDSQL mysq 需要购买数据订阅服务才可以拿到增量日志，私有化部署暂无增量方案
//		connectorFunctions.supportStreamRead(this::streamRead);
//		connectorFunctions.supportTimestampToStreamOffset(this::timestampToStreamOffset);
		connectorFunctions.supportQueryByAdvanceFilter(this::query);
		connectorFunctions.supportWriteRecord(this::writeRecord);
		connectorFunctions.supportCreateIndex(this::createIndex);
		connectorFunctions.supportNewFieldFunction(this::fieldDDLHandler);
		connectorFunctions.supportAlterFieldNameFunction(this::fieldDDLHandler);
		connectorFunctions.supportAlterFieldAttributesFunction(this::fieldDDLHandler);
		connectorFunctions.supportDropFieldFunction(this::fieldDDLHandler);
		connectorFunctions.supportGetTableNamesFunction(this::getTableNames);
		connectorFunctions.supportErrorHandleFunction(this::errorHandle);
		connectorFunctions.supportExecuteCommandFunction((a, b, c) -> SqlExecuteCommandFunction.executeCommand(a, b, () -> mysqlJdbcContext.getConnection(), c));
		connectorFunctions.supportQueryFieldMinMaxValueFunction(this::minMaxValue);
		connectorFunctions.supportGetReadPartitionsFunction(this::getReadPartitions);
	}
	private long batchCount(TapConnectorContext tapConnectorContext, TapTable tapTable) throws Throwable {
		int count;
		try {
			count = mysqlJdbcContext.count(tapTable.getName());
		} catch (Exception e) {
			throw new RuntimeException("Count table " + tapTable.getName() + " error: " + e.getMessage(), e);
		}
		return count;
	}
	private void batchRead(TapConnectorContext tapConnectorContext, TapTable tapTable, Object offset, int batchSize, BiConsumer<List<TapEvent>, Object> consumer) throws Throwable {
		MysqlSnapshotOffset mysqlSnapshotOffset;
		if (offset instanceof MysqlSnapshotOffset) {
			mysqlSnapshotOffset = (MysqlSnapshotOffset) offset;
		} else {
			mysqlSnapshotOffset = new MysqlSnapshotOffset();
		}
		List<TapEvent> tempList = new ArrayList<>();
		this.mysqlReader.readWithOffset(tapConnectorContext, tapTable, mysqlSnapshotOffset, n -> !isAlive(), (data, snapshotOffset) -> {
			TapRecordEvent tapRecordEvent = tapRecordWrapper(tapConnectorContext, null, data, tapTable, "i");
			tempList.add(tapRecordEvent);
			if (tempList.size() == batchSize) {
				consumer.accept(tempList, mysqlSnapshotOffset);
				tempList.clear();
			}
		});
		if (CollectionUtils.isNotEmpty(tempList)) {
			consumer.accept(tempList, mysqlSnapshotOffset);
			tempList.clear();
		}
	}
	private TapRecordEvent tapRecordWrapper(TapConnectorContext tapConnectorContext, Map<String, Object> before, Map<String, Object> after, TapTable tapTable, String op) {
		TapRecordEvent tapRecordEvent;
		switch (op) {
			case "i":
				tapRecordEvent = TapSimplify.insertRecordEvent(after, tapTable.getId());
				break;
			case "u":
				tapRecordEvent = TapSimplify.updateDMLEvent(before, after, tapTable.getId());
				break;
			case "d":
				tapRecordEvent = TapSimplify.deleteDMLEvent(before, tapTable.getId());
				break;
			default:
				throw new IllegalArgumentException("Operation " + op + " not support");
		}
		tapRecordEvent.setConnector(tapConnectorContext.getSpecification().getId());
		tapRecordEvent.setConnectorVersion(version);
		return tapRecordEvent;
	}
	private void clearTable(TapConnectorContext tapConnectorContext, TapClearTableEvent tapClearTableEvent) throws Throwable {
		String tableId = tapClearTableEvent.getTableId();
		if (mysqlJdbcContext.tableExists(tableId)) {
			mysqlJdbcContext.clearTable(tableId);
		} else {
			DataMap connectionConfig = tapConnectorContext.getConnectionConfig();
			String database = connectionConfig.getString("database");
			TapLogger.warn(TAG, "Table \"{}.{}\" not exists, will skip clear table", database, tableId);
		}
	}

	private void dropTable(TapConnectorContext tapConnectorContext, TapDropTableEvent tapDropTableEvent) throws Throwable {
		mysqlJdbcContext.dropTable(tapDropTableEvent.getTableId());
	}

	private CreateTableOptions createTableV2(TapConnectorContext tapConnectorContext, TapCreateTableEvent tapCreateTableEvent) throws Throwable {
		CreateTableOptions createTableOptions = new CreateTableOptions();
		try {
			if (mysqlJdbcContext.tableExists(tapCreateTableEvent.getTableId())) {
				DataMap connectionConfig = tapConnectorContext.getConnectionConfig();
				String database = connectionConfig.getString("database");
				String tableId = tapCreateTableEvent.getTableId();
				createTableOptions.setTableExists(true);
				TapLogger.info(TAG, "Table \"{}.{}\" exists, skip auto create table", database, tableId);
			} else {
				String mysqlVersion = mysqlJdbcContext.getMysqlVersion();
				SqlMaker sqlMaker = new MysqlMaker();
				if (null == tapCreateTableEvent.getTable()) {
					TapLogger.warn(TAG, "Create table event's tap table is null, will skip it: " + tapCreateTableEvent);
					return createTableOptions;
				}
				String[] createTableSqls = sqlMaker.createTable(tapConnectorContext, tapCreateTableEvent, mysqlVersion);
				for (String createTableSql : createTableSqls) {
					try {
						mysqlJdbcContext.execute(createTableSql);
					} catch (Throwable e) {
						throw new Exception("Execute create table failed, sql: " + createTableSql + ", message: " + e.getMessage(), e);
					}
				}
				createTableOptions.setTableExists(false);
			}
			return createTableOptions;
		} catch (Throwable t) {
			throw new Exception("Create table failed, message: " + t.getMessage(), t);
		}
	}

	private void writeRecord(TapConnectorContext tapConnectorContext, List<TapRecordEvent> tapRecordEvents, TapTable tapTable, Consumer<WriteListResult<TapRecordEvent>> consumer) throws Throwable {
		WriteListResult<TapRecordEvent> writeListResult = this.mysqlWriter.write(tapConnectorContext, tapTable, tapRecordEvents);
		consumer.accept(writeListResult);
	}
	private void query(TapConnectorContext tapConnectorContext, TapAdvanceFilter tapAdvanceFilter, TapTable tapTable, Consumer<FilterResults> consumer) {
		FilterResults filterResults = new FilterResults();
		filterResults.setFilter(tapAdvanceFilter);
		try {
			int batchSize = MAX_FILTER_RESULT_SIZE;
			if(tapAdvanceFilter.getBatchSize() != null && tapAdvanceFilter.getBatchSize() > 0) {
				batchSize = tapAdvanceFilter.getBatchSize();
			}
			int finalBatchSize = batchSize;
			this.mysqlReader.readWithFilter(tapConnectorContext, tapTable, tapAdvanceFilter, n -> !isAlive(), data -> {
				filterResults.add(data);
				if (filterResults.getResults().size() == finalBatchSize) {
					consumer.accept(filterResults);
					filterResults.getResults().clear();
				}
			});
			if (CollectionUtils.isNotEmpty(filterResults.getResults())) {
				consumer.accept(filterResults);
				filterResults.getResults().clear();
			}
		} catch (Throwable e) {
			filterResults.setError(e);
			consumer.accept(filterResults);
		}
	}
	private void createIndex(TapConnectorContext tapConnectorContext, TapTable tapTable, TapCreateIndexEvent tapCreateIndexEvent) {
		List<TapIndex> indexList = tapCreateIndexEvent.getIndexList();
		SqlMaker sqlMaker = new MysqlMaker();
		String database = tapConnectorContext.getConnectionConfig().getString("database");
		for (TapIndex tapIndex : indexList.stream().filter(v -> queryExistIndexes(database, tapTable.getId()).stream()
				.noneMatch(i -> DbKit.ignoreCreateIndex(i, v))).collect(Collectors.toList())) {
			String createIndexSql;
			try {
				createIndexSql = sqlMaker.createIndex(tapConnectorContext, tapTable, tapIndex);
			} catch (Throwable e) {
				throw new RuntimeException("Get create index sql failed, message: " + e.getMessage(), e);
			}
			try {
				this.mysqlJdbcContext.execute(createIndexSql);
			} catch (Throwable e) {
				// mysql index  less than  3072 bytes。
				if (e.getMessage() != null && e.getMessage().contains("42000 1071")) {
					TapLogger.warn(TAG, "Execute create index failed, sql: " + createIndexSql + ", message: " + e.getMessage(), e);
				} else {
					throw new RuntimeException("Execute create index failed, sql: " + createIndexSql + ", message: " + e.getMessage(), e);
				}
			}
		}
	}
	private List<TapIndex> queryExistIndexes(String database, String tableName) {
		MysqlSchemaLoader mysqlSchemaLoader = new MysqlSchemaLoader(mysqlJdbcContext);
		try {
			return mysqlSchemaLoader.discoverIndexes(database, tableName);
		} catch (Throwable throwable) {
			return Collections.emptyList();
		}
	}
	@Override
	public int tableCount(TapConnectionContext connectionContext) throws Throwable {
		DataMap connectionConfig = connectionContext.getConnectionConfig();
		String database = connectionConfig.getString("database");
		AtomicInteger count = new AtomicInteger(0);
		this.mysqlJdbcContext.query(String.format("SELECT COUNT(1) count FROM `information_schema`.`TABLES` WHERE TABLE_SCHEMA='%s' AND TABLE_TYPE='BASE TABLE'", database), rs -> {
			if (rs.next()) {
				count.set(Integer.parseInt(rs.getString("count")));
			}
		});
		return count.get();
	}
	private FieldMinMaxValue minMaxValue(TapConnectorContext tapConnectorContext, TapTable tapTable, TapAdvanceFilter tapPartitionFilter, String fieldName) {
		SqlMaker sqlMaker = new MysqlMaker();
		FieldMinMaxValue fieldMinMaxValue = FieldMinMaxValue.create().fieldName(fieldName);
		String selectSql, aaa;
		try {
			selectSql = sqlMaker.selectSql(tapConnectorContext, tapTable, TapPartitionFilter.create().fromAdvanceFilter(tapPartitionFilter));
		} catch (Throwable e) {
			throw new RuntimeException("Build sql with partition filter failed", e);
		}
		// min value
		String minSql = selectSql.replaceFirst("SELECT \\* FROM", String.format("SELECT MIN(`%s`) AS MIN_VALUE FROM", fieldName));
		AtomicReference<Object> minObj = new AtomicReference<>();
		try {
			mysqlJdbcContext.query(minSql, rs->{
				if (rs.next()) {
					minObj.set(rs.getObject("MIN_VALUE"));
				}
			});
		} catch (Throwable e) {
			throw new RuntimeException("Query min value failed, sql: " + minSql, e);
		}
		Optional.ofNullable(minObj.get()).ifPresent(min -> fieldMinMaxValue.min(min).detectType(min));
		// max value
		String maxSql = selectSql.replaceFirst("SELECT \\* FROM", String.format("SELECT MAX(`%s`) AS MAX_VALUE FROM", fieldName));
		AtomicReference<Object> maxObj = new AtomicReference<>();
		try {
			mysqlJdbcContext.query(maxSql, rs->{
				if (rs.next()) {
					maxObj.set(rs.getObject("MAX_VALUE"));
				}
			});
		} catch (Throwable e) {
			throw new RuntimeException("Query max value failed, sql: " + maxSql, e);
		}
		Optional.ofNullable(maxObj.get()).ifPresent(max -> fieldMinMaxValue.max(max).detectType(max));
		return fieldMinMaxValue;
	}

	protected RetryOptions errorHandle(TapConnectionContext tapConnectionContext, PDKMethod pdkMethod, Throwable throwable) {
		RetryOptions retryOptions = RetryOptions.create();
		retryOptions.setNeedRetry(true);
		retryOptions.beforeRetryMethod(()->{
			try {
				this.onStart(tapConnectionContext);
			} catch (Throwable ignore) {
			}
		});
		return retryOptions;
	}

	private void getReadPartitions(TapConnectorContext connectorContext, TapTable table, GetReadPartitionOptions options) {
		DatabaseReadPartitionSplitter.calculateDatabaseReadPartitions(connectorContext, table, options)
				.queryFieldMinMaxValue(this::minMaxValue)
				.typeSplitterMap(options.getTypeSplitterMap().registerSplitter(TypeSplitterMap.TYPE_STRING, StringCaseInsensitiveSplitter.INSTANCE))
				.startSplitting();
	}
	private void getTableNames(TapConnectionContext tapConnectionContext, int batchSize, Consumer<List<String>> listConsumer) {
		MysqlSchemaLoader mysqlSchemaLoader = new MysqlSchemaLoader(mysqlJdbcContext);
		mysqlSchemaLoader.getTableNames(tapConnectionContext, batchSize, listConsumer);
	}
	private void fieldDDLHandler(TapConnectorContext tapConnectorContext, TapFieldBaseEvent tapFieldBaseEvent) {
		List<String> sqls = fieldDDLHandlers.handle(tapFieldBaseEvent, tapConnectorContext);
		if (null == sqls) {
			return;
		}
		for (String sql : sqls) {
			try {
				TapLogger.info(TAG, "Execute ddl sql: " + sql);
				mysqlJdbcContext.execute(sql);
			} catch (Throwable e) {
				throw new RuntimeException("Execute ddl sql failed: " + sql + ", error: " + e.getMessage(), e);
			}
		}
	}

}
