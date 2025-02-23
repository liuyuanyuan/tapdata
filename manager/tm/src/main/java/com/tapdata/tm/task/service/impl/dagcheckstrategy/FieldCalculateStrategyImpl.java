package com.tapdata.tm.task.service.impl.dagcheckstrategy;

import com.tapdata.tm.commons.dag.DAG;
import com.tapdata.tm.commons.dag.process.FieldCalcProcessorNode;
import com.tapdata.tm.commons.dag.process.RowFilterProcessorNode;
import com.tapdata.tm.commons.task.dto.TaskDto;
import com.tapdata.tm.config.security.UserDetail;
import com.tapdata.tm.message.constant.Level;
import com.tapdata.tm.task.constant.DagOutputTemplateEnum;
import com.tapdata.tm.task.entity.TaskDagCheckLog;
import com.tapdata.tm.task.service.DagLogStrategy;
import com.tapdata.tm.utils.Lists;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.stereotype.Component;

import java.text.MessageFormat;
import java.util.Date;
import java.util.List;
import java.util.Objects;

@Component("fieldCalculteStrategy")
public class FieldCalculateStrategyImpl implements DagLogStrategy {

    private final DagOutputTemplateEnum templateEnum = DagOutputTemplateEnum.FIELD_CALCULTE_CHECK;

    @Override
    public List<TaskDagCheckLog> getLogs(TaskDto taskDto, UserDetail userDetail) {
        if (TaskDto.SYNC_TYPE_MIGRATE.equals(taskDto.getSyncType())) {
            return null;
        }

        String taskId = taskDto.getId().toHexString();

        Date now = new Date();
        String userId = userDetail.getUserId();
        List<TaskDagCheckLog> result = Lists.newArrayList();
        DAG dag = taskDto.getDag();

        if (Objects.isNull(dag) || CollectionUtils.isEmpty(dag.getNodes())) {
            return null;
        }

        dag.getNodes().stream()
                .filter(node -> node instanceof FieldCalcProcessorNode)
                .forEach(node -> {
                    String name = node.getName();
                    String nodeId = node.getId();

                    if (StringUtils.isEmpty(name)) {
                        TaskDagCheckLog log = TaskDagCheckLog.builder().taskId(taskId).checkType(templateEnum.name())
                                .grade(Level.ERROR).nodeId(nodeId)
                                .log("$date【$taskName】【字段计算节点检测】：字段计算节点节点名称为空。")
                                .build();
                        log.setCreateAt(now);
                        log.setCreateUser(userId);
                        result.add(log);
                    }

                    if (CollectionUtils.isEmpty(result) || result.stream().anyMatch(log -> nodeId.equals(log.getNodeId()))) {
                        TaskDagCheckLog log = TaskDagCheckLog.builder().taskId(taskId).checkType(templateEnum.name())
                                .grade(Level.INFO).nodeId(nodeId)
                                .log(MessageFormat.format("$date【$taskName】【字段计算节点检测】：字段计算节点{0}检测通过。", name))
                                .build();
                        log.setCreateAt(now);
                        log.setCreateUser(userId);
                        result.add(log);
                    }
                });

        return result;
    }
}
