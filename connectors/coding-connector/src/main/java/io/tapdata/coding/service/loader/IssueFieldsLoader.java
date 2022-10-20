package io.tapdata.coding.service.loader;

import io.tapdata.coding.CodingConnector;
import io.tapdata.coding.entity.ContextConfig;
import io.tapdata.coding.entity.param.IssueFieldParam;
import io.tapdata.coding.utils.collection.MapUtil;
import io.tapdata.coding.utils.http.CodingHttp;
import io.tapdata.coding.utils.http.HttpEntity;
import io.tapdata.coding.utils.tool.Checker;
import io.tapdata.entity.event.TapEvent;
import io.tapdata.entity.simplify.TapSimplify;
import io.tapdata.pdk.apis.consumer.StreamReadConsumer;
import io.tapdata.pdk.apis.context.TapConnectionContext;

import java.util.*;
import java.util.function.BiConsumer;

import static io.tapdata.coding.enums.TapEventTypes.CREATED_EVENT;
import static io.tapdata.coding.enums.TapEventTypes.UPDATE_EVENT;

public class IssueFieldsLoader extends CodingStarter implements CodingLoader<IssueFieldParam>{
    OverlayQueryEventDifferentiator overlayQueryEventDifferentiator = new OverlayQueryEventDifferentiator();
    public final static String TABLE_NAME = "IssueFields";
    public IssueFieldsLoader(TapConnectionContext tapConnectionContext) {
        super(tapConnectionContext);
    }
    public static IssueFieldsLoader create(TapConnectionContext tapConnectionContext){
        return new IssueFieldsLoader(tapConnectionContext);
    }
    CodingConnector codingConnector;
    public IssueFieldsLoader connectorInit(CodingConnector codingConnector){
        this.codingConnector = codingConnector;
        return this;
    }
    private boolean stopRead = false;
    public void stopRead(){
        stopRead = true;
    }
    @Override
    public Long streamReadTime() {
        return 5 * 60 * 1000L;
    }

    @Override
    public List<Map<String, Object>> list(IssueFieldParam param) {
        Map<String,Object> resultMap = this.codingHttp(param).post();
        Object response = resultMap.get("Response");
        if (Checker.isEmpty(response)){
            return null;
        }
        Object fieldsMapObj = ((Map<String,Object>)response).get("ProjectIssueFieldList");
        return null !=  fieldsMapObj? (List<Map<String, Object>>) fieldsMapObj : null;
    }

    @Override
    public List<Map<String, Object>> all(IssueFieldParam param) {
        List<String> issueTypes = param.issueTypes();
        if (Checker.isEmpty(issueTypes)) return null;
        List<Map<String, Object>> result = new ArrayList<>();
        issueTypes.forEach(issueType->{
            param.issueType(issueType);
            List<Map<String, Object>> list = list(param);
            //@TODO
            if (Checker.isNotEmpty(list) && !list.isEmpty()) {
                result.addAll(list);
            }
        });
        return result;
    }

    @Override
    public CodingHttp codingHttp(IssueFieldParam param) {
        ContextConfig contextConfig = this.veryContextConfigAndNodeConfig();
        HttpEntity<String,String> header = HttpEntity.create()
                .builder("Authorization",contextConfig.getToken());
        HttpEntity<String,Object> body = HttpEntity.create()
                .builderIfNotAbsent("Action","DescribeProjectIssueFieldList")
                .builder("ProjectName", contextConfig.getProjectName())
                .builder("IssueType",param.issueType());//@TODO
        return CodingHttp.create(
                header.getEntity(),
                body.getEntity(),
                String.format(OPEN_API_URL,contextConfig.getTeamName()));
    }

    @Override
    public void batchRead(Object offset, int batchCount, BiConsumer<List<TapEvent>, Object> consumer) {
        this.read(offset, batchCount, consumer);
    }

    private void read(Object offset, int batchCount, BiConsumer<List<TapEvent>, Object> consumer){
        List<Map<String, Object>> list = list(null);
        if (null == list || list.isEmpty()){
            return ;
        }
        List<TapEvent> events = new ArrayList<>();
        for (Map<String, Object> issueType : list) {
            Integer issueTypeId = (Integer) issueType.get("IssueFieldId");
            Integer issueTypeHash = MapUtil.create().hashCode(issueType);
            switch (overlayQueryEventDifferentiator.createOrUpdateEvent(issueTypeId,issueTypeHash)){
                case CREATED_EVENT:events.add(TapSimplify.insertRecordEvent(issueType, TABLE_NAME).referenceTime(System.currentTimeMillis()));break;
                case UPDATE_EVENT:events.add(TapSimplify.updateDMLEvent(null,issueType, TABLE_NAME).referenceTime(System.currentTimeMillis()));break;
            }
            //events.add(TapSimplify.insertRecordEvent(issueType, TABLE_NAME).referenceTime(System.currentTimeMillis()));
            if (events.size() == batchCount) {
                consumer.accept(events, offset);
                events = new ArrayList<>();
            }
        }

        List<TapEvent> delEvents = overlayQueryEventDifferentiator.delEvent(TABLE_NAME,"IssueFieldId");
        if (!delEvents.isEmpty()){
            events.addAll(delEvents);
        }
        if (events.size() > 0)  consumer.accept(events, offset);
    }

    @Override
    public int batchCount() throws Throwable {
        List<Map<String, Object>> list = list(null);
        if (null == list) {
            return 0;
        }
        return list.size();
    }

    @Override
    public void streamRead(List<String> tableList, Object offsetState, int recordSize, StreamReadConsumer consumer) {
        this.read(offsetState, recordSize, consumer);
    }

    @Override
    public List<TapEvent> rawDataCallbackFilterFunction(Map<String, Object> issueEventData) {
        return null;
    }
}
