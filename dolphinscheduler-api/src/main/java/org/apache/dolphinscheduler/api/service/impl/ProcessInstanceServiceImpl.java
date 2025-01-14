/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dolphinscheduler.api.service.impl;

import static org.apache.dolphinscheduler.common.Constants.DATA_LIST;
import static org.apache.dolphinscheduler.common.Constants.DEPENDENT_SPLIT;
import static org.apache.dolphinscheduler.common.Constants.GLOBAL_PARAMS;
import static org.apache.dolphinscheduler.common.Constants.LOCAL_PARAMS;
import static org.apache.dolphinscheduler.common.Constants.PROCESS_INSTANCE_STATE;
import static org.apache.dolphinscheduler.common.Constants.TASK_LIST;

import org.apache.dolphinscheduler.api.dto.gantt.GanttDto;
import org.apache.dolphinscheduler.api.dto.gantt.Task;
import org.apache.dolphinscheduler.api.enums.Status;
import org.apache.dolphinscheduler.api.service.ExecutorService;
import org.apache.dolphinscheduler.api.service.LoggerService;
import org.apache.dolphinscheduler.api.service.ProcessDefinitionService;
import org.apache.dolphinscheduler.api.service.ProcessInstanceService;
import org.apache.dolphinscheduler.api.service.ProjectService;
import org.apache.dolphinscheduler.api.service.UsersService;
import org.apache.dolphinscheduler.api.utils.PageInfo;
import org.apache.dolphinscheduler.api.utils.Result;
import org.apache.dolphinscheduler.common.Constants;
import org.apache.dolphinscheduler.common.enums.DependResult;
import org.apache.dolphinscheduler.common.enums.ExecutionStatus;
import org.apache.dolphinscheduler.common.enums.Flag;
import org.apache.dolphinscheduler.common.enums.TaskType;
import org.apache.dolphinscheduler.common.graph.DAG;
import org.apache.dolphinscheduler.common.model.TaskNode;
import org.apache.dolphinscheduler.common.model.TaskNodeRelation;
import org.apache.dolphinscheduler.common.process.Property;
import org.apache.dolphinscheduler.common.utils.CollectionUtils;
import org.apache.dolphinscheduler.common.utils.DateUtils;
import org.apache.dolphinscheduler.common.utils.JSONUtils;
import org.apache.dolphinscheduler.common.utils.ParameterUtils;
import org.apache.dolphinscheduler.common.utils.StringUtils;
import org.apache.dolphinscheduler.common.utils.placeholder.BusinessTimeUtils;
import org.apache.dolphinscheduler.dao.entity.ProcessData;
import org.apache.dolphinscheduler.dao.entity.ProcessDefinition;
import org.apache.dolphinscheduler.dao.entity.ProcessInstance;
import org.apache.dolphinscheduler.dao.entity.Project;
import org.apache.dolphinscheduler.dao.entity.TaskDefinitionLog;
import org.apache.dolphinscheduler.dao.entity.TaskInstance;
import org.apache.dolphinscheduler.dao.entity.Tenant;
import org.apache.dolphinscheduler.dao.entity.User;
import org.apache.dolphinscheduler.dao.mapper.ProcessDefinitionLogMapper;
import org.apache.dolphinscheduler.dao.mapper.ProcessDefinitionMapper;
import org.apache.dolphinscheduler.dao.mapper.ProcessInstanceMapper;
import org.apache.dolphinscheduler.dao.mapper.ProjectMapper;
import org.apache.dolphinscheduler.dao.mapper.TaskDefinitionLogMapper;
import org.apache.dolphinscheduler.dao.mapper.TaskInstanceMapper;
import org.apache.dolphinscheduler.service.process.ProcessService;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

/**
 * process instance service impl
 */
@Service
public class ProcessInstanceServiceImpl extends BaseServiceImpl implements ProcessInstanceService {

    private static final Logger logger = LoggerFactory.getLogger(ProcessInstanceService.class);

    public static final String TASK_TYPE = "taskType";
    public static final String LOCAL_PARAMS_LIST = "localParamsList";

    @Autowired
    ProjectMapper projectMapper;

    @Autowired
    ProjectService projectService;

    @Autowired
    ProcessService processService;

    @Autowired
    ProcessInstanceMapper processInstanceMapper;

    @Autowired
    ProcessDefinitionMapper processDefineMapper;

    @Autowired
    ProcessDefinitionService processDefinitionService;

    @Autowired
    ExecutorService execService;

    @Autowired
    TaskInstanceMapper taskInstanceMapper;

    @Autowired
    LoggerService loggerService;

    @Autowired
    ProcessDefinitionLogMapper processDefinitionLogMapper;

    @Autowired
    TaskDefinitionLogMapper taskDefinitionLogMapper;

    @Autowired
    UsersService usersService;

    /**
     * return top n SUCCESS process instance order by running time which started between startTime and endTime
     */
    @Override
    public Map<String, Object> queryTopNLongestRunningProcessInstance(User loginUser, String projectName, int size, String startTime, String endTime) {
        Map<String, Object> result = new HashMap<>();

        Project project = projectMapper.queryByName(projectName);
        Map<String, Object> checkResult = projectService.checkProjectAndAuth(loginUser, project, projectName);
        Status resultEnum = (Status) checkResult.get(Constants.STATUS);
        if (resultEnum != Status.SUCCESS) {
            return checkResult;
        }

        if (0 > size) {
            putMsg(result, Status.NEGTIVE_SIZE_NUMBER_ERROR, size);
            return result;
        }
        if (Objects.isNull(startTime)) {
            putMsg(result, Status.DATA_IS_NULL, Constants.START_TIME);
            return result;
        }
        Date start = DateUtils.stringToDate(startTime);
        if (Objects.isNull(endTime)) {
            putMsg(result, Status.DATA_IS_NULL, Constants.END_TIME);
            return result;
        }
        Date end = DateUtils.stringToDate(endTime);
        if (start == null || end == null) {
            putMsg(result, Status.REQUEST_PARAMS_NOT_VALID_ERROR, Constants.START_END_DATE);
            return result;
        }
        if (start.getTime() > end.getTime()) {
            putMsg(result, Status.START_TIME_BIGGER_THAN_END_TIME_ERROR, startTime, endTime);
            return result;
        }

        List<ProcessInstance> processInstances = processInstanceMapper.queryTopNProcessInstance(size, start, end, ExecutionStatus.SUCCESS);
        result.put(DATA_LIST, processInstances);
        putMsg(result, Status.SUCCESS);
        return result;
    }

    /**
     * query process instance by id
     *
     * @param loginUser login user
     * @param projectName project name
     * @param processId process instance id
     * @return process instance detail
     */
    @Override
    public Map<String, Object> queryProcessInstanceById(User loginUser, String projectName, Integer processId) {
        Map<String, Object> result = new HashMap<>();
        Project project = projectMapper.queryByName(projectName);

        Map<String, Object> checkResult = projectService.checkProjectAndAuth(loginUser, project, projectName);
        Status resultEnum = (Status) checkResult.get(Constants.STATUS);
        if (resultEnum != Status.SUCCESS) {
            return checkResult;
        }
        ProcessInstance processInstance = processService.findProcessInstanceDetailById(processId);

        ProcessDefinition processDefinition = processService.findProcessDefinition(processInstance.getProcessDefinitionCode(),
                processInstance.getProcessDefinitionVersion());

        if (processDefinition == null) {
            putMsg(result, Status.PROCESS_DEFINE_NOT_EXIST, processId);
        } else {
            processInstance.setWarningGroupId(processDefinition.getWarningGroupId());
            processInstance.setConnects(processDefinition.getConnects());
            processInstance.setLocations(processDefinition.getLocations());

            ProcessData processData = processService.genProcessData(processDefinition);
            processInstance.setProcessInstanceJson(JSONUtils.toJsonString(processData));
            result.put(DATA_LIST, processInstance);
            putMsg(result, Status.SUCCESS);
        }

        return result;
    }

    /**
     * paging query process instance list, filtering according to project, process definition, time range, keyword, process status
     *
     * @param loginUser login user
     * @param projectName project name
     * @param pageNo page number
     * @param pageSize page size
     * @param processDefineId process definition id
     * @param searchVal search value
     * @param stateType state type
     * @param host host
     * @param startDate start time
     * @param endDate end time
     * @return process instance list
     */
    @Override
    public Result queryProcessInstanceList(User loginUser, String projectName, Integer processDefineId,
                                                        String startDate, String endDate,
                                                        String searchVal, String executorName, ExecutionStatus stateType, String host,
                                                        Integer pageNo, Integer pageSize) {

        Result result = new Result();
        Project project = projectMapper.queryByName(projectName);

        Map<String, Object> checkResult = projectService.checkProjectAndAuth(loginUser, project, projectName);
        Status resultEnum = (Status) checkResult.get(Constants.STATUS);
        if (resultEnum != Status.SUCCESS) {
            putMsg(result,resultEnum);
            return result;
        }

        int[] statusArray = null;
        // filter by state
        if (stateType != null) {
            statusArray = new int[]{stateType.ordinal()};
        }

        Map<String, Object> checkAndParseDateResult = checkAndParseDateParameters(startDate, endDate);
        resultEnum = (Status) checkAndParseDateResult.get(Constants.STATUS);
        if (resultEnum != Status.SUCCESS) {
            putMsg(result,resultEnum);
            return result;
        }
        Date start = (Date) checkAndParseDateResult.get(Constants.START_TIME);
        Date end = (Date) checkAndParseDateResult.get(Constants.END_TIME);

        Page<ProcessInstance> page = new Page<>(pageNo, pageSize);
        PageInfo<ProcessInstance> pageInfo = new PageInfo<>(pageNo, pageSize);
        int executorId = usersService.getUserIdByName(executorName);

        ProcessDefinition processDefinition = processDefineMapper.queryByDefineId(processDefineId);

        IPage<ProcessInstance> processInstanceList = processInstanceMapper.queryProcessInstanceListPaging(page,
                project.getCode(), processDefinition == null ? 0L : processDefinition.getCode(), searchVal,
                executorId, statusArray, host, start, end);

        List<ProcessInstance> processInstances = processInstanceList.getRecords();
        List<Integer> userIds = CollectionUtils.transformToList(processInstances, ProcessInstance::getExecutorId);
        Map<Integer, User> idToUserMap = CollectionUtils.collectionToMap(usersService.queryUser(userIds), User::getId);

        for (ProcessInstance processInstance : processInstances) {
            processInstance.setDuration(DateUtils.format2Duration(processInstance.getStartTime(), processInstance.getEndTime()));
            User executor = idToUserMap.get(processInstance.getExecutorId());
            if (null != executor) {
                processInstance.setExecutorName(executor.getUserName());
            }
        }

        pageInfo.setTotal((int) processInstanceList.getTotal());
        pageInfo.setTotalList(processInstances);
        result.setData(pageInfo);
        putMsg(result, Status.SUCCESS);
        return result;
    }

    /**
     * query task list by process instance id
     *
     * @param loginUser login user
     * @param projectName project name
     * @param processId process instance id
     * @return task list for the process instance
     * @throws IOException io exception
     */
    @Override
    public Map<String, Object> queryTaskListByProcessId(User loginUser, String projectName, Integer processId) throws IOException {
        Map<String, Object> result = new HashMap<>();
        Project project = projectMapper.queryByName(projectName);

        Map<String, Object> checkResult = projectService.checkProjectAndAuth(loginUser, project, projectName);
        Status resultEnum = (Status) checkResult.get(Constants.STATUS);
        if (resultEnum != Status.SUCCESS) {
            return checkResult;
        }
        ProcessInstance processInstance = processService.findProcessInstanceDetailById(processId);
        List<TaskInstance> taskInstanceList = processService.findValidTaskListByProcessId(processId);
        addDependResultForTaskList(taskInstanceList);
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put(PROCESS_INSTANCE_STATE, processInstance.getState().toString());
        resultMap.put(TASK_LIST, taskInstanceList);
        result.put(DATA_LIST, resultMap);

        putMsg(result, Status.SUCCESS);
        return result;
    }

    /**
     * add dependent result for dependent task
     */
    private void addDependResultForTaskList(List<TaskInstance> taskInstanceList) throws IOException {
        for (TaskInstance taskInstance : taskInstanceList) {
            if (TaskType.DEPENDENT.getDesc().equalsIgnoreCase(taskInstance.getTaskType())) {
                Result<String> logResult = loggerService.queryLog(
                        taskInstance.getId(), Constants.LOG_QUERY_SKIP_LINE_NUMBER, Constants.LOG_QUERY_LIMIT);
                if (logResult.isSuccess()) {
                    String log = logResult.getData();
                    Map<String, DependResult> resultMap = parseLogForDependentResult(log);
                    taskInstance.setDependentResult(JSONUtils.toJsonString(resultMap));
                }
            }
        }
    }

    @Override
    public Map<String, DependResult> parseLogForDependentResult(String log) throws IOException {
        Map<String, DependResult> resultMap = new HashMap<>();
        if (StringUtils.isEmpty(log)) {
            return resultMap;
        }

        BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(log.getBytes(
                StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
        String line;
        while ((line = br.readLine()) != null) {
            if (line.contains(DEPENDENT_SPLIT)) {
                String[] tmpStringArray = line.split(":\\|\\|");
                if (tmpStringArray.length != 2) {
                    continue;
                }
                String dependResultString = tmpStringArray[1];
                String[] dependStringArray = dependResultString.split(",");
                if (dependStringArray.length != 2) {
                    continue;
                }
                String key = dependStringArray[0].trim();
                DependResult dependResult = DependResult.valueOf(dependStringArray[1].trim());
                resultMap.put(key, dependResult);
            }
        }
        return resultMap;
    }

    /**
     * query sub process instance detail info by task id
     *
     * @param loginUser login user
     * @param projectName project name
     * @param taskId task id
     * @return sub process instance detail
     */
    @Override
    public Map<String, Object> querySubProcessInstanceByTaskId(User loginUser, String projectName, Integer taskId) {
        Map<String, Object> result = new HashMap<>();
        Project project = projectMapper.queryByName(projectName);

        Map<String, Object> checkResult = projectService.checkProjectAndAuth(loginUser, project, projectName);
        Status resultEnum = (Status) checkResult.get(Constants.STATUS);
        if (resultEnum != Status.SUCCESS) {
            return checkResult;
        }

        TaskInstance taskInstance = processService.findTaskInstanceById(taskId);
        if (taskInstance == null) {
            putMsg(result, Status.TASK_INSTANCE_NOT_EXISTS, taskId);
            return result;
        }
        if (!taskInstance.isSubProcess()) {
            putMsg(result, Status.TASK_INSTANCE_NOT_SUB_WORKFLOW_INSTANCE, taskInstance.getName());
            return result;
        }

        ProcessInstance subWorkflowInstance = processService.findSubProcessInstance(
                taskInstance.getProcessInstanceId(), taskInstance.getId());
        if (subWorkflowInstance == null) {
            putMsg(result, Status.SUB_PROCESS_INSTANCE_NOT_EXIST, taskId);
            return result;
        }
        Map<String, Object> dataMap = new HashMap<>();
        dataMap.put(Constants.SUBPROCESS_INSTANCE_ID, subWorkflowInstance.getId());
        result.put(DATA_LIST, dataMap);
        putMsg(result, Status.SUCCESS);
        return result;
    }

    /**
     * update process instance
     *
     * @param loginUser login user
     * @param projectName project name
     * @param processInstanceJson process instance json
     * @param processInstanceId process instance id
     * @param scheduleTime schedule time
     * @param syncDefine sync define
     * @param flag flag
     * @param locations locations
     * @param connects connects
     * @return update result code
     */
    @Transactional
    @Override
    public Map<String, Object> updateProcessInstance(User loginUser, String projectName, Integer processInstanceId,
                                                     String processInstanceJson, String scheduleTime, Boolean syncDefine,
                                                     Flag flag, String locations, String connects) {
        Map<String, Object> result = new HashMap<>();
        Project project = projectMapper.queryByName(projectName);
        //check project permission
        Map<String, Object> checkResult = projectService.checkProjectAndAuth(loginUser, project, projectName);
        Status resultEnum = (Status) checkResult.get(Constants.STATUS);
        if (resultEnum != Status.SUCCESS) {
            return checkResult;
        }
        //check process instance exists
        ProcessInstance processInstance = processService.findProcessInstanceDetailById(processInstanceId);
        if (processInstance == null) {
            putMsg(result, Status.PROCESS_INSTANCE_NOT_EXIST, processInstanceId);
            return result;
        }
        //check process instance status
        if (!processInstance.getState().typeIsFinished()) {
            putMsg(result, Status.PROCESS_INSTANCE_STATE_OPERATION_ERROR,
                    processInstance.getName(), processInstance.getState().toString(), "update");
            return result;
        }
        ProcessDefinition processDefinition = processService.findProcessDefinition(processInstance.getProcessDefinitionCode(),
                processInstance.getProcessDefinitionVersion());
        ProcessData processData = JSONUtils.parseObject(processInstanceJson, ProcessData.class);
        //check workflow json is valid
        result = processDefinitionService.checkProcessNodeList(processData, processInstanceJson);
        if (result.get(Constants.STATUS) != Status.SUCCESS) {
            return result;
        }
        Tenant tenant = processService.getTenantForProcess(processData.getTenantId(),
                processDefinition.getUserId());
        setProcessInstance(processInstance, tenant, scheduleTime, processData);
        int updateDefine = 1;
        if (Boolean.TRUE.equals(syncDefine)) {
            processDefinition.setId(processDefineMapper.queryByCode(processInstance.getProcessDefinitionCode()).getId());
            updateDefine = syncDefinition(loginUser, project, locations, connects,
                    processInstance, processDefinition, processData);

            processInstance.setProcessDefinitionVersion(processDefinitionLogMapper.
                    queryMaxVersionForDefinition(processInstance.getProcessDefinitionCode()));
        }

        int update = processService.updateProcessInstance(processInstance);
        if (update > 0 && updateDefine > 0) {
            putMsg(result, Status.SUCCESS);
        } else {
            putMsg(result, Status.UPDATE_PROCESS_INSTANCE_ERROR);
        }
        return result;
    }

    /**
     * sync definition according process instance
     */
    private int syncDefinition(User loginUser, Project project, String locations, String connects,
                               ProcessInstance processInstance, ProcessDefinition processDefinition,
                               ProcessData processData) {

        String originDefParams = JSONUtils.toJsonString(processData.getGlobalParams());
        processDefinition.setGlobalParams(originDefParams);
        processDefinition.setLocations(locations);
        processDefinition.setConnects(connects);
        processDefinition.setTimeout(processInstance.getTimeout());
        processDefinition.setUpdateTime(new Date());

        return processService.saveProcessDefinition(loginUser, project, processDefinition.getName(),
                processDefinition.getDescription(), locations, connects,
                processData, processDefinition, false);
    }

    /**
     * update process instance attributes
     */
    private void setProcessInstance(ProcessInstance processInstance, Tenant tenant, String scheduleTime, ProcessData processData) {

        Date schedule = processInstance.getScheduleTime();
        if (scheduleTime != null) {
            schedule = DateUtils.getScheduleDate(scheduleTime);
        }
        processInstance.setScheduleTime(schedule);
        List<Property> globalParamList = processData.getGlobalParams();
        Map<String, String> globalParamMap = Optional.ofNullable(globalParamList)
                .orElse(Collections.emptyList())
                .stream()
                .collect(Collectors.toMap(Property::getProp, Property::getValue));
        String globalParams = ParameterUtils.curingGlobalParams(globalParamMap, globalParamList,
                processInstance.getCmdTypeIfComplement(), schedule);
        processInstance.setTimeout(processData.getTimeout());
        if (tenant != null) {
            processInstance.setTenantCode(tenant.getTenantCode());
        }
        processInstance.setGlobalParams(globalParams);
    }

    /**
     * query parent process instance detail info by sub process instance id
     *
     * @param loginUser login user
     * @param projectName project name
     * @param subId sub process id
     * @return parent instance detail
     */
    @Override
    public Map<String, Object> queryParentInstanceBySubId(User loginUser, String projectName, Integer subId) {
        Map<String, Object> result = new HashMap<>();
        Project project = projectMapper.queryByName(projectName);

        Map<String, Object> checkResult = projectService.checkProjectAndAuth(loginUser, project, projectName);
        Status resultEnum = (Status) checkResult.get(Constants.STATUS);
        if (resultEnum != Status.SUCCESS) {
            return checkResult;
        }

        ProcessInstance subInstance = processService.findProcessInstanceDetailById(subId);
        if (subInstance == null) {
            putMsg(result, Status.PROCESS_INSTANCE_NOT_EXIST, subId);
            return result;
        }
        if (subInstance.getIsSubProcess() == Flag.NO) {
            putMsg(result, Status.PROCESS_INSTANCE_NOT_SUB_PROCESS_INSTANCE, subInstance.getName());
            return result;
        }

        ProcessInstance parentWorkflowInstance = processService.findParentProcessInstance(subId);
        if (parentWorkflowInstance == null) {
            putMsg(result, Status.SUB_PROCESS_INSTANCE_NOT_EXIST);
            return result;
        }
        Map<String, Object> dataMap = new HashMap<>();
        dataMap.put(Constants.PARENT_WORKFLOW_INSTANCE, parentWorkflowInstance.getId());
        result.put(DATA_LIST, dataMap);
        putMsg(result, Status.SUCCESS);
        return result;
    }

    /**
     * delete process instance by id, at the same time，delete task instance and their mapping relation data
     *
     * @param loginUser login user
     * @param projectName project name
     * @param processInstanceId process instance id
     * @return delete result code
     */
    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public Map<String, Object> deleteProcessInstanceById(User loginUser, String projectName, Integer processInstanceId) {

        Map<String, Object> result = new HashMap<>();
        Project project = projectMapper.queryByName(projectName);

        Map<String, Object> checkResult = projectService.checkProjectAndAuth(loginUser, project, projectName);
        Status resultEnum = (Status) checkResult.get(Constants.STATUS);
        if (resultEnum != Status.SUCCESS) {
            return checkResult;
        }
        ProcessInstance processInstance = processService.findProcessInstanceDetailById(processInstanceId);
        if (null == processInstance) {
            putMsg(result, Status.PROCESS_INSTANCE_NOT_EXIST, processInstanceId);
            return result;
        }

        processService.removeTaskLogFile(processInstanceId);
        // delete database cascade
        int delete = processService.deleteWorkProcessInstanceById(processInstanceId);

        processService.deleteAllSubWorkProcessByParentId(processInstanceId);
        processService.deleteWorkProcessMapByParentId(processInstanceId);

        if (delete > 0) {
            putMsg(result, Status.SUCCESS);
        } else {
            putMsg(result, Status.DELETE_PROCESS_INSTANCE_BY_ID_ERROR);
        }

        return result;
    }

    /**
     * view process instance variables
     *
     * @param processInstanceId process instance id
     * @return variables data
     */
    @Override
    public Map<String, Object> viewVariables(Integer processInstanceId) {
        Map<String, Object> result = new HashMap<>();

        ProcessInstance processInstance = processInstanceMapper.queryDetailById(processInstanceId);

        if (processInstance == null) {
            throw new RuntimeException("workflow instance is null");
        }

        Map<String, String> timeParams = BusinessTimeUtils
                .getBusinessTime(processInstance.getCmdTypeIfComplement(),
                        processInstance.getScheduleTime());
        String userDefinedParams = processInstance.getGlobalParams();
        // global params
        List<Property> globalParams = new ArrayList<>();

        // global param string
        String globalParamStr = ParameterUtils.convertParameterPlaceholders(JSONUtils.toJsonString(globalParams), timeParams);
        globalParams = JSONUtils.toList(globalParamStr, Property.class);
        for (Property property : globalParams) {
            timeParams.put(property.getProp(), property.getValue());
        }

        if (userDefinedParams != null && userDefinedParams.length() > 0) {
            globalParams = JSONUtils.toList(userDefinedParams, Property.class);
        }

        Map<String, Map<String, Object>> localUserDefParams = getLocalParams(processInstance, timeParams);

        Map<String, Object> resultMap = new HashMap<>();

        resultMap.put(GLOBAL_PARAMS, globalParams);
        resultMap.put(LOCAL_PARAMS, localUserDefParams);

        result.put(DATA_LIST, resultMap);
        putMsg(result, Status.SUCCESS);
        return result;
    }

    /**
     * get local params
     */
    private Map<String, Map<String, Object>> getLocalParams(ProcessInstance processInstance, Map<String, String> timeParams) {
        Map<String, Map<String, Object>> localUserDefParams = new HashMap<>();
        List<TaskInstance> taskInstanceList = taskInstanceMapper.findValidTaskListByProcessId(processInstance.getId(), Flag.YES);
        for (TaskInstance taskInstance : taskInstanceList) {
            TaskDefinitionLog taskDefinitionLog = taskDefinitionLogMapper.queryByDefinitionCodeAndVersion(
                    taskInstance.getTaskCode(), taskInstance.getTaskDefinitionVersion());

            String localParams = JSONUtils.getNodeString(taskDefinitionLog.getTaskParams(), LOCAL_PARAMS);
            if (StringUtils.isNotEmpty(localParams)) {
                localParams = ParameterUtils.convertParameterPlaceholders(localParams, timeParams);
                List<Property> localParamsList = JSONUtils.toList(localParams, Property.class);

                Map<String, Object> localParamsMap = new HashMap<>();
                localParamsMap.put(TASK_TYPE, taskDefinitionLog.getTaskType());
                localParamsMap.put(LOCAL_PARAMS_LIST, localParamsList);
                if (CollectionUtils.isNotEmpty(localParamsList)) {
                    localUserDefParams.put(taskDefinitionLog.getName(), localParamsMap);
                }
            }
        }
        return localUserDefParams;
    }

    /**
     * encapsulation gantt structure
     *
     * @param processInstanceId process instance id
     * @return gantt tree data
     * @throws Exception exception when json parse
     */
    @Override
    public Map<String, Object> viewGantt(Integer processInstanceId) throws Exception {
        Map<String, Object> result = new HashMap<>();

        ProcessInstance processInstance = processInstanceMapper.queryDetailById(processInstanceId);

        if (processInstance == null) {
            throw new RuntimeException("workflow instance is null");
        }

        ProcessDefinition processDefinition = processDefinitionLogMapper.queryByDefinitionCodeAndVersion(
                processInstance.getProcessDefinitionCode(),
                processInstance.getProcessDefinitionVersion()
        );
        GanttDto ganttDto = new GanttDto();
        DAG<String, TaskNode, TaskNodeRelation> dag = processService.genDagGraph(processDefinition);
        //topological sort
        List<String> nodeList = dag.topologicalSort();

        ganttDto.setTaskNames(nodeList);

        List<Task> taskList = new ArrayList<>();
        for (String node : nodeList) {
            TaskInstance taskInstance = taskInstanceMapper.queryByInstanceIdAndName(processInstanceId, node);
            if (taskInstance == null) {
                continue;
            }
            Date startTime = taskInstance.getStartTime() == null ? new Date() : taskInstance.getStartTime();
            Date endTime = taskInstance.getEndTime() == null ? new Date() : taskInstance.getEndTime();
            Task task = new Task();
            task.setTaskName(taskInstance.getName());
            task.getStartDate().add(startTime.getTime());
            task.getEndDate().add(endTime.getTime());
            task.setIsoStart(startTime);
            task.setIsoEnd(endTime);
            task.setStatus(taskInstance.getState().toString());
            task.setExecutionDate(taskInstance.getStartTime());
            task.setDuration(DateUtils.format2Readable(endTime.getTime() - startTime.getTime()));
            taskList.add(task);
        }
        ganttDto.setTasks(taskList);

        result.put(DATA_LIST, ganttDto);
        putMsg(result, Status.SUCCESS);
        return result;
    }

    /**
     * query process instance by processDefinitionCode and stateArray
     *
     * @param processDefinitionCode processDefinitionCode
     * @param states states array
     * @return process instance list
     */
    @Override
    public List<ProcessInstance> queryByProcessDefineCodeAndStatus(Long processDefinitionCode, int[] states) {
        return processInstanceMapper.queryByProcessDefineCodeAndStatus(processDefinitionCode, states);
    }

    /**
     * query process instance by processDefinitionCode
     *
     * @param processDefinitionCode processDefinitionCode
     * @param size size
     * @return process instance list
     */
    @Override
    public List<ProcessInstance> queryByProcessDefineCode(Long processDefinitionCode, int size) {
        return processInstanceMapper.queryByProcessDefineCode(processDefinitionCode, size);
    }

}
