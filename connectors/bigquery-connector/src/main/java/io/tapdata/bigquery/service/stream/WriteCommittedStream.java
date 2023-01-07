package io.tapdata.bigquery.service.stream;

import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutureCallback;
import com.google.api.core.ApiFutures;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.bigquery.storage.v1.*;
import com.google.cloud.bigquery.storage.v1.Exceptions.StorageException;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import io.grpc.LoadBalancerRegistry;
import io.grpc.internal.PickFirstLoadBalancerProvider;
import io.tapdata.entity.error.CoreException;
import io.tapdata.entity.logger.TapLogger;
import io.tapdata.entity.utils.cache.KVMap;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.annotation.concurrent.GuardedBy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicLong;

public class WriteCommittedStream {
    public static final String TAG = WriteCommittedStream.class.getSimpleName();
    private String dataSet;
    private String tableName;
    private String projectId;
    private DataWriter writer;
    private String credentialsJson;
    private KVMap<Object> stateMap;
    private AtomicLong streamOffset;
    private BigQueryWriteClient client;

    public WriteCommittedStream streamOffset(AtomicLong streamOffset) {
        this.streamOffset = streamOffset;
        return this;
    }

    public String projectId() {
        return this.projectId;
    }

    public String dataSet() {
        return this.dataSet;
    }

    public String tableName() {
        return this.tableName;
    }

    public String credentialsJson() {
        return this.credentialsJson;
    }

    public WriteCommittedStream projectId(String projectId) {
        this.projectId = projectId;
        return this;
    }

    public WriteCommittedStream dataSet(String dataSet) {
        this.dataSet = dataSet;
        return this;
    }

    public WriteCommittedStream tableName(String tableName) throws DescriptorValidationException, InterruptedException, IOException {
        this.tableName = tableName;
        return this;
    }

    public WriteCommittedStream credentialsJson(String credentialsJson) {
        this.credentialsJson = credentialsJson;
        return this;
    }

    public WriteCommittedStream stateMap(KVMap<Object> stateMap) {
        this.stateMap = stateMap;
        return this;
    }

    public static WriteCommittedStream writer(String projectId, String dataSet, String tableName, String credentialsJson) throws DescriptorValidationException, InterruptedException, IOException {
        WriteCommittedStream writeCommittedStream = new WriteCommittedStream()
                .projectId(projectId)
                .dataSet(dataSet)
                .tableName(tableName)
                .credentialsJson(credentialsJson);
        return writeCommittedStream.init();
    }

    private WriteCommittedStream() {

    }

    private WriteCommittedStream init() {
        GoogleCredentials googleCredentials = getGoogleCredentials(this.credentialsJson);
        try {
            BigQueryWriteSettings settings =
                    BigQueryWriteSettings.newBuilder().setCredentialsProvider(() -> googleCredentials).build();
            this.client = BigQueryWriteClient.create(settings);
            TableName parentTable = TableName.of(this.projectId, this.dataSet, this.tableName);
            // One time initialization.
            this.writer = new DataWriter();
            this.writer.initialize(parentTable, this.client, this.credentialsJson);
        }catch (Exception e){
            TapLogger.error(TAG,String.format("Unable to create Stream connection, exception information: %s.",e.getMessage()));
        }
        return this;
    }

    private GoogleCredentials getGoogleCredentials(String credentialsJson) {
        try {
            return GoogleCredentials.fromStream(new ByteArrayInputStream(credentialsJson.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new CoreException("Big query connector direct fail exception, connector not handle this exception");
        }
    }

    public void append(List<Map<String, Object>> record) {
        if (Objects.isNull(record) || record.isEmpty()) return;
        List<List<Map<String, Object>>> partition = Lists.partition(record, 5000);
        partition.forEach(recordPartition -> {
            JSONArray jsonArr = new JSONArray();
            for (Map<String, Object> map : recordPartition) {
                if (Objects.isNull(map)) continue;
                JSONObject jsonObject = new JSONObject();
                map.forEach(jsonObject::put);
                jsonArr.put(jsonObject);
            }
            try {
                long offsetState = this.streamOffset.get();
                this.writer.append(jsonArr, offsetState);
                this.streamOffset.addAndGet(jsonArr.length());
            } catch (Exception e) {
                TapLogger.error(TAG, "Stream API write record (Append_only) error, data offset is : " + this.streamOffset);
            }
        });
    }

    public void appendJSON(List<Map<String, Object>> record) {
        if (Objects.isNull(record) || record.isEmpty()) return;
        List<List<Map<String, Object>>> partition = Lists.partition(record, 5000);
        JSONArray json = new JSONArray();
        partition.forEach(recordPartition -> {
            for (Map<String, Object> map : recordPartition) {
                if (Objects.isNull(map)) continue;
                JSONObject jsonObject = new JSONObject();
                map.forEach((key, value) -> {
                    if (value instanceof Map) {
                        JSONObject jsonObjects = new JSONObject();
                        Map<String, Object> objectMap = (Map<String, Object>) value;
                        objectMap.forEach(jsonObjects::put);
                        jsonObject.put(key, jsonObjects);
                    } else if (value instanceof Collection) {
                        jsonObject.put(key, value);
                    } else {
                        jsonObject.put(key, value);
                    }
                });
                json.put(jsonObject);
            }
        });
        try {
            long offsetState = this.streamOffset.get();
            this.writer.append(json, offsetState);
            this.streamOffset.addAndGet(json.length());
        } catch (Exception e) {
            TapLogger.error(TAG, "Stream API write record (Mixed updates) error,data offset is : " + this.streamOffset );
        }
    }

    public void close() {
        if (Objects.nonNull(this.writer))
            this.writer.cleanup(this.client);
    }

    // A simple wrapper object showing how the stateful stream writer should be used.
    private static class DataWriter {
        private JsonStreamWriter streamWriter;
        // Track the number of in-flight requests to wait for all responses before shutting down.
        private final Phaser inflightRequestCount = new Phaser(1);

        private final Object lock = new Object();

        @GuardedBy("lock")
        private RuntimeException error = null;

        void initialize(TableName parentTable, BigQueryWriteClient client, String credentialsJson)
                throws IOException, DescriptorValidationException, InterruptedException {
            // Initialize a write stream for the specified table.
            // For more information on WriteStream.Type, see:
            // https://googleapis.dev/java/google-cloud-bigquerystorage/latest/com/google/cloud/bigquery/storage/v1/WriteStream.Type.html
            WriteStream stream = WriteStream.newBuilder().setType(WriteStream.Type.COMMITTED).build();

            CreateWriteStreamRequest createWriteStreamRequest =
                    CreateWriteStreamRequest.newBuilder()
                            .setParent(parentTable.toString())
                            .setWriteStream(stream)
                            .build();
            LoadBalancerRegistry.getDefaultRegistry().register(new PickFirstLoadBalancerProvider());
            WriteStream writeStream = client.createWriteStream(createWriteStreamRequest);

            // Use the JSON stream writer to send records in JSON format.
            // For more information about JsonStreamWriter, see:
            // https://googleapis.dev/java/google-cloud-bigquerystorage/latest/com/google/cloud/bigquery/storage/v1/JsonStreamWriter.html

            GoogleCredentials credentials =
                    ServiceAccountCredentials.fromStream(new ByteArrayInputStream(credentialsJson.getBytes(StandardCharsets.UTF_8)));
            this.streamWriter = JsonStreamWriter
                    .newBuilder(writeStream.getName(), writeStream.getTableSchema())
                    .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                    .build();
        }

        public void append(JSONArray data, long offset)
                throws DescriptorValidationException, IOException, ExecutionException {
            synchronized (this.lock) {
                // If earlier appends have failed, we need to reset before continuing.
                if (this.error != null) {
                    throw this.error;
                }
            }
            // Append asynchronously for increased throughput.
            ApiFuture<AppendRowsResponse> future = this.streamWriter.append(data, offset);
            ApiFutures.addCallback(
                    future, new DataWriter.AppendCompleteCallback(this), MoreExecutors.directExecutor());
            // Increase the count of in-flight requests.
            this.inflightRequestCount.register();
        }

        public void cleanup(BigQueryWriteClient client) {
            // Wait for all in-flight requests to complete.
            this.inflightRequestCount.arriveAndAwaitAdvance();

            // Close the connection to the server.
            this.streamWriter.close();

            // Verify that no error occurred in the stream.
            synchronized (this.lock) {
                if (this.error != null) {
                    throw this.error;
                }
            }

            // Finalize the stream.
            FinalizeWriteStreamResponse finalizeResponse =
                    client.finalizeWriteStream(this.streamWriter.getStreamName());
        }

        public String getStreamName() {
            return this.streamWriter.getStreamName();
        }

        static class AppendCompleteCallback implements ApiFutureCallback<AppendRowsResponse> {

            private final DataWriter parent;

            public AppendCompleteCallback(DataWriter parent) {
                this.parent = parent;
            }

            public void onSuccess(AppendRowsResponse response) {
                //TapLogger.info(TAG,String.format("Append %d success ", response.getAppendResult().getOffset().getValue()));
                done();
            }

            public void onFailure(Throwable throwable) {
                synchronized (this.parent.lock) {
                    if (this.parent.error == null) {
                        StorageException storageException = Exceptions.toStorageException(throwable);
                        this.parent.error =
                                (storageException != null) ? storageException : new RuntimeException(throwable);
                    }
                }
                TapLogger.warn(TAG, "Warn: " + throwable.getMessage());
                done();
                throw new CoreException("Error: " + throwable.getMessage());
            }

            private void done() {
                // Reduce the count of in-flight requests.
                this.parent.inflightRequestCount.arriveAndDeregister();
            }
        }
    }
}
