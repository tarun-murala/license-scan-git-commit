/**
 * API for all Planned Task Actions
 * 	-- load : Load the Graph for a given sysId
 * 	-- recalculate: Recalculate the graph
 */
package com.snc.planned_task.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.json.simple.JSONObject;
import org.mozilla.javascript.ScriptableObject;

import com.glide.db.ElementDescriptor;
import com.glide.glideobject.GlideDateTime;
import com.glide.glideobject.GlideDuration;
import com.glide.script.GlideElement;
import com.glide.script.GlideRecord;
import com.glide.script.GlideRhinoHelper;
import com.glide.script.GlideSystem;
import com.glide.script.system.GlideSystemDateUtil;
import com.glide.sys.GlideSession;
import com.glide.sys.SysLog;
import com.glide.util.JSONUtil;
import com.glide.util.Log;
import com.glide.util.StopWatch;
import com.glide.util.StringUtil;
import com.snc.planned_task.constants.PlannedTaskFieldConstants;
import com.snc.planned_task.constants.PlannedTaskTableConstants;
import com.snc.planned_task.core.action.Action;
import com.snc.planned_task.core.action.ActionBuilder;
import com.snc.planned_task.core.console.ColumnMaps;
import com.snc.planned_task.core.console.Console;
import com.snc.planned_task.core.console.ConsoleDataLoader;
import com.snc.planned_task.core.console.ConsoleGanttFormatDataLoader;
import com.snc.planned_task.core.console.ConsoleMetadata;
import com.snc.planned_task.core.console.ConsoleMetadataGenerator;
import com.snc.planned_task.core.console.ConsoleMyGanttDataLoader;
import com.snc.planned_task.core.console.data.IPlannedTaskData;
import com.snc.planned_task.core.console.data.PlannedTaskGanttData;
import com.snc.planned_task.core.console.data.PlannedTaskJSONData;
import com.snc.planned_task.core.console.data.PlannedTaskJsonStructureComparator;
import com.snc.planned_task.core.console.data.ScheduleDecorator;
import com.snc.planned_task.core.cosole.loader.ConsoleConfigurationLoader;
import com.snc.planned_task.core.cosole.loader.LoadStrategy;
import com.snc.planned_task.core.cosole.loader.Loader;
import com.snc.planned_task.core.datastore.DataStore;
import com.snc.planned_task.core.datastore.PlannedTaskRecordDecorator;
import com.snc.planned_task.core.engine.CycleDetector;
import com.snc.planned_task.core.engine.DAGConverter;
import com.snc.planned_task.core.engine.PlannedTaskEngine;
import com.snc.planned_task.core.engine.ProjectCopier;
import com.snc.planned_task.core.engine.WbsValidator;
import com.snc.planned_task.core.engine.entites.EngineGraph;
import com.snc.planned_task.core.engine.entites.EngineRelation;
import com.snc.planned_task.core.engine.entites.EngineTask;
import com.snc.planned_task.core.engine.relations.InterProjectCycleDetector;
import com.snc.planned_task.core.engine.relations.InterProjectRelationCallback;
import com.snc.planned_task.core.handlers.GraphChangeManager;
import com.snc.planned_task.core.handlers.ParentChangeHandler;
import com.snc.planned_task.core.handlers.PlannedTaskNotificationHandler;
import com.snc.planned_task.core.helpers.AccessHelper;
import com.snc.planned_task.core.helpers.DateTimeHelper;
import com.snc.planned_task.core.helpers.DurationHelper;
import com.snc.planned_task.core.helpers.LogHelper;
import com.snc.planned_task.core.helpers.PlannedTaskRelationToJsonConverter;
import com.snc.planned_task.core.helpers.PlannedTaskToJsonObjectConverter;
import com.snc.planned_task.core.helpers.PlannedTaskValueValidator;
import com.snc.planned_task.core.helpers.ProjectStateHelper;
import com.snc.planned_task.core.helpers.RecordAccessHelper;
import com.snc.planned_task.core.helpers.RecordCacheCleaner;
import com.snc.planned_task.core.helpers.ReferenceHelper;
import com.snc.planned_task.core.helpers.ReferenceValues;
import com.snc.planned_task.core.helpers.RelationCheckHelper;
import com.snc.planned_task.core.helpers.SimpleHelper;
import com.snc.planned_task.core.helpers.TimeZoneHelper;
import com.snc.planned_task.core.loader.PlannedTaskStructureLoader;
import com.snc.planned_task.core.ranking.RankUpdateHelper;
import com.snc.planned_task.core.services.PlannedTaskDBService;
import com.snc.planned_task.core.services.PlannedTaskRelationDBService;
import com.snc.planned_task.core.view.ProjectView;
import com.snc.planned_task.exceptions.CyclicException;
import com.snc.planned_task.exceptions.PlannedTaskException;
import com.snc.planned_task.factories.IPlannedTaskFactory;
import com.snc.planned_task.factories.PlannedTaskFactory;
import com.snc.planned_task.schedule.PTNoSchedule;
import com.snc.planned_task.schedule.PTSchedule;
import com.snc.planned_task.schedule.PTScheduleAPI;
import com.snc.planned_task.schedule.PTScheduleCache;
import com.snc.planned_task.schedule.PTScheduleException;
import com.snc.planned_task.schedule.ScheduleConstants;
import com.snc.planned_task.schedule.ScheduleLoader;
import com.snc.planned_task.strategies.ProjectAction;
import com.snc.planned_task.strategies.ProjectActionStrategy;
import com.snc.planned_task.strategies.ReloadStrategy;
import com.snc.planned_task.strategies.SaveStrategy;
import com.snc.planned_task.validators.PlannedTaskRelationValidator;
import com.snc.program_management.IPlannedTaskStructureBuilder;
import com.snc.program_management.PortfolioBuilder;
import com.snc.program_management.ProgramBuilder;
import com.snc.project_management.ProjectTemplate;
import com.snc.project_management.engine.view.TimelineViewModel;

/**
 * @author tarun.murala
 *
 */
public class PlannedTaskAPI extends ScriptableObject implements PlannedTaskFieldConstants, PlannedTaskTableConstants {
	private PlannedTaskEngine taskEngine = new PlannedTaskEngine();
	private SeededMessages msgs = new SeededMessages();
	private RecalculationConstraint constraints = new RecalculationConstraint();
	private static final long serialVersionUID = 1L;
	
	
	public PlannedTaskAPI() {
		// default constructor
	}
	
	public PlannedTaskStructure loadStructure(final String sysId) {
		return this.loadStructure(sysId, false);
	}
	
	// No Entity Loader
	public PlannedTaskStructure loadStructure(final String sysId, boolean excludeShadows) {
		PlannedTaskStructureLoader loader = new PlannedTaskStructureLoader();
		return loader.loadStructure(sysId, null, excludeShadows);
	}
	
	// No Entity Loader with additional attributes
	public PlannedTaskStructure loadStructure(final String sysId, boolean excludeShadows, final List<String> additionalAttributes) {
		PlannedTaskStructureLoader loader = new PlannedTaskStructureLoader();
		return loader.loadStructure(sysId, null, excludeShadows, additionalAttributes);
	}

	// TODO: Re-factor these methods. Need to discuss this and arrive at an elegant solution
	private PlannedTaskStructure loadStructure(final String sysId, GlideRecord taskRecord) {
	    PlannedTaskStructure structure = new PlannedTaskStructure();
	    if(taskRecord.instanceOf(PROGRAM)) {
	        IPlannedTaskLoader loader = new ProgramLoader(structure);
	        loader.loadByTopTask(taskRecord.getValue(TOP_TASK));	        
	        // TODO: We need to decide if we want to load all projects, demands, program tasks here
	    } else if (taskRecord.instanceOf(PROGRAM_TASK)) {
	        // TODO: Need to find a way to add the loaders and PlannedTaskLoadProcessors
	        IPlannedTaskLoader loader = new ProgramTaskLoader(structure);
            loader.loadByParent(taskRecord.getValue(PARENT));
            loader = new ProgramLoader(structure);
	        loader.loadById(taskRecord.getValue(PARENT));
	    } else {
	        PlannedTaskStructureLoader loader = new PlannedTaskStructureLoader();
	        return loader.loadStructure(sysId, null);
	    }
	    return structure;
	}

	public PlannedTaskStructure loadStructure(final String sysId, String entity) {
		PlannedTaskStructureLoader loader = new PlannedTaskStructureLoader();
		return loader.loadStructure(sysId, entity);
	}
	
	private boolean detectCycle(PlannedTaskStructure structure) {
		return (new CycleDetector()).detectCyle(structure);
	}

	/**
	 * Called from Before BR (Insert) to initialize the default values
	 * @param taskRecord
	 * @throws PlannedTaskException 
	 */
	public void initializeNewTask(GlideRecord taskRecord) throws PlannedTaskException {
		(new PlannedTaskInitializer()).initializeTaskRecord(taskRecord);
	}
	
	/**
	 * Called from the BR to update other task's wbs
	 * @param taskRecord
	 */
	public void changeWbsOrder(GlideRecord taskRecord) {
		String newWbsOrder = taskRecord.getValue(WBS_ORDER);
		int wbsOrder = 0;
		try {
			wbsOrder = Integer.parseInt(newWbsOrder);
			if( wbsOrder < 1) {
				taskRecord.setAbortAction(true);
			} else {
				String taskSysId = taskRecord.getValue(PlannedTaskFieldConstants.SYS_ID);
				PlannedTaskStructureLoader structureLoader = new PlannedTaskStructureLoader();
				PlannedTaskStructure structure = structureLoader.loadStructure(
						taskSysId);
				taskEngine.changeWbs(structure, taskSysId, String.valueOf(wbsOrder-1));
				this.getDataStore(false).save(structure);
			}
		} catch( Exception e) {
			taskRecord.setAbortAction(true);
		}
	}
	
	/**
	 * Called from BR(Update) on the planned task
	 * @param record
	 * @param oldParentSysId
	 * @param newParentSysId
	 */
	public String changeParent(Object record, String oldParentSysId, String newParentSysId) {
		String status;
		try {
			status = (new ParentChangeHandler()).changeParent(record, oldParentSysId, newParentSysId);
		} catch(Exception e) {
			Log.error("Unable to Change Parent");
			status = new GlideSystem().jsFunction_getMessage("Unable to change parent", null);
		}
		return status;
	}
	
	/**
	 * Called from Validate Change Parent BR on the planned task
	 * @param record
	 * @param oldParentSysId
	 * @param newParentSysId
	 */
	public String validateChangeParent(Object record, String oldParentSysId, String newParentSysId) {
		String status;
		try {
			status = (new ParentChangeHandler()).validateChangeParent(record, oldParentSysId, newParentSysId);
		} catch(Exception e) {
			Log.error("Unable to Change Parent");
			status = new GlideSystem().jsFunction_getMessage("Unable to change parent", null);
		}
		return status;
	}
	
	/**
	 * Call form BR, When Date(s) changes and only recalculates the date(s)
	 * @param topTaskSysId
	 * @return
	 * @throws PlannedTaskException
	 * @throws PTScheduleException 
	 */
	public PlannedTaskStructure recalulate(String taskSysId, boolean save) throws PlannedTaskException, PTScheduleException {
		PlannedTaskStructure structure = this.loadStructure(taskSysId);
		if(structure.hasTasks() && null != structure.getTask(taskSysId)) {
			structure.getTask(taskSysId).setDirty(true);
			taskEngine.recalculate(structure, TimeZoneHelper.getUserTimezone(), 
					this.constraints);
			if(save) {
				this.getDataStore(false).save(structure);
			}
		}
		return structure;
	}
	
	private DataStore getDataStore(boolean fireBrs) {
		return new DataStore(fireBrs);
	}

	/**
	 * Call from a BR, When a Field changes, Call taskSysId and rollupColumn
	 * @param taskSysId
	 * @param rollupColumn
	 */
	public void rollup(GlideRecord taskRecord, String rollupColumn) throws PlannedTaskException {
		LogHelper.logInfo(PlannedTaskAPI.class.getName(), "Into rollup for column: " + rollupColumn + " for task: " + 
				taskRecord.getValue(SHORT_DESCRIPTION));
		taskEngine.rollup(taskRecord, rollupColumn);
	}
	
	public PlannedTaskStructure performAction(PlannedTaskStructure projectGraph, Action payload, ColumnMaps maps, boolean fireBRs) throws PlannedTaskException, PTScheduleException {
		ProjectAction action = ProjectActionStrategy.actionStrategy(fireBRs);
		action.setTimeZone(GlideSession.get().getTimeZoneName());
		action.setRecalculationConstraint(constraints);
		PlannedTaskStructure recalcedProject = action.perform(projectGraph, payload, maps);
		SaveStrategy saver = action.getSaveStrategy();
		saver.save(recalcedProject);
		
		ReloadStrategy reloader = action.getReloadStrategy();
		recalcedProject = reloader.reloadProject(recalcedProject);
		
		return recalcedProject;
	}
	
	public PlannedTaskStructure performAction(PlannedTaskStructure projectGraph, Action payload, ColumnMaps maps, ProjectAction action) throws PlannedTaskException, PTScheduleException {
		action.setTimeZone(GlideSession.get().getTimeZoneName());
		action.setRecalculationConstraint(constraints);
		PlannedTaskStructure recalcedProject = action.perform(projectGraph, payload, maps);
		return recalcedProject;
	}

	public void fullRecalculate(String sysId) throws PlannedTaskException, PTScheduleException {
		PlannedTaskRecordDecorator taskRecordDecorator = new PlannedTaskRecordDecorator();
		taskRecordDecorator.get(sysId);
		String topTaskSysId = taskRecordDecorator.getValue(TOP_TASK);
		PlannedTaskStructure structure = this.loadStructure(topTaskSysId);
		CycleDetector cycleDetector = new CycleDetector();
		if(structure.hasTasks() && !cycleDetector.detectCyle(structure)) {
			for (PlannedTask task : structure.getTasks()) {
				task.setDirty(true);
			}
			taskEngine.recalculate(structure, TimeZoneHelper.getUserTimezone(), this.constraints);
			taskEngine.validateWbs(structure);
			this.getDataStore(false).save(structure);
		}
	}
	
	public PTSchedule getScheduleUtil(String scheduleId, String timeZone) {
		String scheduleSysId = null;
		if( PlannedTaskValueValidator.isValidString(scheduleId)) {
			scheduleSysId = scheduleId;
			PTSchedule scheduleUtil = this.getScheduleAPI().getSchedule(scheduleSysId, timeZone);
			if( scheduleUtil.isValid()) {
				return scheduleUtil;
			} else {
				SysLog.error(PlannedTaskAPI.class.getName(), "Invalid Schedule!");
				return null;
			}
		} else {
			return new PTNoSchedule();
		}
	}
	
	private PTScheduleAPI getScheduleAPI() {
		PTScheduleAPI  scheduleAPI = new PTScheduleAPI(ScheduleConstants.PROJECT_MANAGEMENT);
		return scheduleAPI;
	}
	
	public Map<String, Object> getChangeMapFromRecord(GlideRecord record) {
		Map<String, Object> recordMap = new HashMap<String, Object>();
		List<GlideElement> elements = record.getElements();
		for (GlideElement glideElement : elements) {
			if(glideElement.changes()) {
				recordMap.put(glideElement.getName(), glideElement.getValue());
			}
		}
		recordMap.put(SYS_ID, record.getElement(SYS_ID).getValue());
		return recordMap;
	}
	
	// JS Constructor(s)
	public void jsFunction_initializeTask(Object glideRecord) throws PlannedTaskException {
		if( null != glideRecord) {
			this.initializeNewTask((GlideRecord)glideRecord);
		}
	}
	
	public void jsFunction_rollup(Object taskRecord, String rollupColumn) throws PlannedTaskException {
		if( null != taskRecord) {
			this.rollup((GlideRecord) taskRecord, rollupColumn);
		}
	}
	
	public void jsFunction_changeOrder(Object record) {
		if( null != record) {
			this.changeWbsOrder((GlideRecord)record);
		}
	}
	
	public String jsFunction_changeParent(Object record, String oldParentSysId, String newParentSysId) {
		return this.changeParent(record, oldParentSysId, newParentSysId);
	}
	
	public String jsFunction_validateChangeParent(Object record, String oldParentSysId, String newParentSysId) {
		return this.validateChangeParent(record, oldParentSysId, newParentSysId);
	}
	
	public String jsFunction_calculateEndDate(String startDate, String duration, String scheduleId){
		LogHelper.logMsg(this.getClass().getName(), "Into jsFunction_calculateEndDate");
		GlideDuration glideDuration = getDuration(duration);
		GlideDateTime startDateTime = DateTimeHelper.getGlideDateTimeFromDisplay(startDate);
		FormAjaxResponse resp = new FormAjaxResponse();
		if ( DateTimeHelper.isValidDuration(glideDuration) && DateTimeHelper.isValidDate(startDateTime) ) {
			GlideDateTime endDate = calculateEndDate(scheduleId, glideDuration, startDateTime);			
			resp.setStatus("success");
			resp.setDateTime(endDate.getDisplayValue());			
		}
		else {
			resp.setStatus("error");
			if ( DateTimeHelper.isValidDuration(glideDuration) )
				resp.setMessage(this.msgs.getTranslatedMessage(PlannedTaskException.INVALID_START_DATE));
			else
				resp.setMessage(this.msgs.getTranslatedMessage(PlannedTaskException.INVALID_DURATION));
		}
		return this.stringify(resp);
	}
	
	public String jsFunction_calculateEndDateInternal(String startDate, String duration, String scheduleId){
		LogHelper.logMsg(this.getClass().getName(), "Into jsFunction_calculateEndDateInternal");
		GlideDuration glideDuration = getDuration(duration);
		GlideDateTime startDateTime = DateTimeHelper.getGlideDateTime(startDate);
//		if(!DateTimeHelper.isValidDisplayDate(startDateTime) || !DateTimeHelper.isInValidPeriod(startDateTime)) 
//			return DateTimeHelper.getNowDateTime().getDisplayValue();
//		GlideDateTime endDate = calculateEndDate(scheduleId, glideDuration, startDateTime);
//		return endDate.getValue();
		return jsFunction_calculateEndDate(startDateTime.getDisplayValueInternal(), glideDuration.getValue(), scheduleId);
	}

	private GlideDateTime calculateEndDate(String scheduleId, GlideDuration glideDuration, GlideDateTime startDateTime) {
		String timeZone = TimeZoneHelper.getUserTimezone();
		PTSchedule scheduleUtil = this.getScheduleUtil(scheduleId, timeZone);
		GlideDuration absoluteDuration = DurationHelper.getAbsoluteDuration(startDateTime, glideDuration, scheduleUtil);
		GlideDateTime endDate = scheduleUtil.calculateEndDate(startDateTime, absoluteDuration.getValue());
		return endDate;
	}

	private GlideDuration getDuration(String duration) {
		GlideDuration glideDuration = DurationHelper.getZeroDuration();
		if( PlannedTaskValueValidator.isValidString(duration)) {
			if ( duration.matches("\\d\\d\\d\\d-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d") )
				glideDuration.setValue(duration);
			else
				glideDuration.setDisplayValue(duration);
		} else {
			glideDuration.setValue(ZERO_DURATION);
		}
		return glideDuration;
	}
	
	public String jsFunction_calculateDuration(String startDate, String endDate, String scheduleId){
		LogHelper.logMsg(this.getClass().getName(), "Into jsFunction_calculateDuration");
		FormAjaxResponse resp = new FormAjaxResponse();
		GlideDateTime startDateTime = DateTimeHelper.getGlideDateTimeFromDisplay(startDate);
//		if(!DateTimeHelper.isValidDisplayDate(startDateTime) || !DateTimeHelper.isInValidPeriod(startDateTime)) 
//			return DurationHelper.getZeroDuration();
		GlideDateTime endDateTime = DateTimeHelper.getGlideDateTimeFromDisplay(endDate);
//		if(!DateTimeHelper.isValidDisplayDate(endDateTime) || !DateTimeHelper.isInValidPeriod(endDateTime)) 
//			return DurationHelper.getZeroDuration();
		if ( DateTimeHelper.isValidDate(startDateTime) &&  DateTimeHelper.isValidDate(endDateTime) && DateTimeHelper.isValidPeriod(startDateTime, endDateTime) ) {
			GlideDuration duration = calculateDuration(scheduleId, startDateTime, endDateTime);
			resp.setStatus("success");
			resp.setDuration(duration.getDayPart() + " " + duration.getByFormat("HH") + ":" + duration.getByFormat("mm") + ":" + duration.getByFormat("ss"));
		}
		else {
			resp.setStatus("error");
			resp.setMessage(this.msgs.getTranslatedMessage(PlannedTaskException.INVALID_DURATION));
		}
		return this.stringify(resp);
	}
	
	public String jsFunction_calculateDurationInternal(String startDate, String endDate, String scheduleId){
		LogHelper.logMsg(this.getClass().getName(), "Into jsFunction_calculateDurationInternal");
		GlideDateTime startDateTime = DateTimeHelper.getGlideDateTime(startDate);
//		if(!DateTimeHelper.isValidDisplayDate(startDateTime) || !DateTimeHelper.isInValidPeriod(startDateTime)) 
//			return DurationHelper.getZeroDuration();
		GlideDateTime endDateTime = DateTimeHelper.getGlideDateTime(endDate);
//		if(!DateTimeHelper.isValidDisplayDate(endDateTime) || !DateTimeHelper.isInValidPeriod(endDateTime)) 
//			return DurationHelper.getZeroDuration();
		return jsFunction_calculateDuration(startDateTime.getDisplayValueInternal(), endDateTime.getDisplayValueInternal(), scheduleId);
		//return calculateDuration(scheduleId, startDateTime, endDateTime);
	}
	
	public String jsFunction_isTaskStartDateValidForProject(String projectId, String taskDuration, String newStartDate) {
		GlideDateTime startDateTime = DateTimeHelper.getGlideDateTime(newStartDate);
		GlideDuration duration = new GlideDuration();
		duration.setValue(taskDuration);
		
		GlideRecord projectGr = new GlideRecord(PlannedTaskTableConstants.PROJECT);
		if(!projectGr.get(projectId)) {
			projectGr = new GlideRecord(PlannedTaskTableConstants.PLANNED_TASK);
			if(!projectGr.get(projectId)) {
				return "false";
			}
		}
		GlideDateTime projectStart = new GlideDateTime();
		projectStart.setValue(projectGr.getValue("start_date"));
		
		String timeZone = TimeZoneHelper.getUserTimezone();
		PTSchedule schedule = this.getScheduleUtil(projectGr.getValue("schedule"), timeZone);

		GlideDuration absDuration = DurationHelper.getAbsoluteDuration(startDateTime, duration, schedule);
		GlideDateTime plannedEndDate = schedule.calculateEndDate(startDateTime, absDuration.getValue());

		PTNoSchedule noSchedule = new PTNoSchedule();
		GlideDuration projectDuration = noSchedule.calculateDuration(projectStart, plannedEndDate);
		
		if ( !DateTimeHelper.isValidDuration(projectDuration) ) 
			return "false";
		return "true";
	}
	
	private GlideDuration calculateDuration(String scheduleId, GlideDateTime startDateTime, GlideDateTime endDateTime) {
		String timeZone = TimeZoneHelper.getUserTimezone();
		PTSchedule scheduleUtil = this.getScheduleUtil(scheduleId, timeZone);
		GlideDuration duration = (scheduleUtil.calculateDuration(startDateTime, endDateTime));
		GlideDuration durationDisplay = DurationHelper.calculateDurationDisplayForDate(scheduleUtil, startDateTime, duration);
		return durationDisplay;
	}
	
	public String jsFunction_calculateStartDate( 
			String endDate, String duration, String scheduleId){
		LogHelper.logMsg(this.getClass().getName(), "Into jsFunction_calculateStartDate");
		String timeZone = TimeZoneHelper.getUserTimezone();
		String startDate = "";
		GlideDuration glideDuration = new GlideDuration();
		if( PlannedTaskValueValidator.isValidString(duration)) {
			glideDuration.setDisplayValue(duration);
		} else {
			glideDuration.setValue(ZERO_DURATION);
		}
		try{
			PTSchedule scheduleUtil = this.getScheduleUtil(scheduleId, timeZone);
			GlideDateTime endDateTime = DateTimeHelper.getGlideDateTime(endDate);
			if(!DateTimeHelper.isValidDisplayDate(endDateTime) || !DateTimeHelper.isValidDate(endDateTime)) 
				return DateTimeHelper.getNowDateTime().getDisplayValue();
			startDate = (scheduleUtil.calculateStartDate(endDateTime, glideDuration.getValue())).getDisplayValue();
		} catch (PTScheduleException e) {
			GlideSystem.getGlideSystem().jsFunction_logError("Unable to calculate Start Date : " + e.getMessage(), 
					PlannedTaskAPI.class.getName());
		}
		return startDate;
	}
	
	public String jsFunction_getHoursPerDay( 
			String startDate, String scheduleId){
		LogHelper.logMsg(this.getClass().getName(), "Into jsFunction_getHoursPerDay");
		String timeZone = TimeZoneHelper.getUserTimezone();
		PTSchedule scheduleUtil = this.getScheduleUtil(scheduleId, timeZone);
		GlideDateTime startDateTime = DateTimeHelper.getGlideDateTime(startDate);
		return String.valueOf(scheduleUtil.getHoursPerDayFromSchedule(startDateTime));
	}
	
	public String jsFunction_isRelationValid(String projectSysId, Object relationRecord) {
		PlannedTaskStructure structure = loadStructure(projectSysId);
		PlannedTaskRelationValidator relationValidator = new PlannedTaskRelationValidator(structure);
		PlannedTaskRelation relation = new PlannedTaskRelation((GlideRecord)relationRecord);
		return relationValidator.validateRelation(relation);
	}
	
	public String jsFunction_isHierarchyValid(String projectSysId, Object record) {
		PlannedTaskStructure structure = loadStructure(projectSysId);
		PlannedTaskRelationValidator relationValidator = new PlannedTaskRelationValidator(structure);
		String nodeId = ((GlideRecord)record).getValue("sys_id");
		String parent = ((GlideRecord)record).getValue("parent");
		return relationValidator.validateHierarchicalRelation(nodeId, parent);
	}
	
	public String jsFunction_isParentValid(String projectSysId, Object record) {
		GlideRecord taskRecord = (GlideRecord) record;
		PlannedTaskStructure structure = loadStructure(projectSysId);
		PlannedTask node = structure.getTask(taskRecord.getValue(SYS_ID));
		List<PlannedTask> subTreeNodes = structure.getSubTreeNodes(node.getSysId());
		for (PlannedTask subTreeNode : subTreeNodes) {
			if( subTreeNode.getSysId().equals(taskRecord.getValue(PARENT))) {
				return "relation_cyclic";
			}
		}
		return "valid";
	}
	
	public String jsFunction_getTaskStartDate(String taskSysId){
		LogHelper.logMsg(this.getClass().getName(), "Into jsFunction_getTaskStartDate");
		if (PlannedTaskValueValidator.isValidString(taskSysId)) {
			GlideRecord task = new GlideRecord(PlannedTaskTableConstants.PLANNED_TASK);
			if(task.get(taskSysId)) {
				if (PlannedTaskValueValidator.isValidString(task.getValue(PARENT))) {
					 task.get(task.getValue(PARENT));
					 return task.getDisplayValue(START_DATE);
				} 
				if (PlannedTaskValueValidator.isValidString(task.getValue(TOP_TASK))) {
					task.get(task.getValue(TOP_TASK));
					return task.getDisplayValue(START_DATE);
				}
			}
		}
		return (new GlideDateTime()).getDisplayValue();
	}
	
	public void jsFunction_checkAndLoadSchedule(String scheduleId, String projectId, String timeZone) {
		LogHelper.logMsg(this.getClass().getName(), "Into jsFunction_checkAndLoadSchedule: "
				+ scheduleId + "Project: " + projectId + "; TimeZone: " + timeZone);
		// String startDate, endDate;
		if( PlannedTaskValueValidator.isValidString(projectId)) {
			PlannedTaskRecordDecorator project = new PlannedTaskRecordDecorator();
			project.get(projectId);
			// startDate = project.getValue(ProjectManagementFieldConstants.START_DATE);
			// endDate = project.getValue(ProjectManagementFieldConstants.END_DATE);
		}
		else {
			// As per New PTSchedule Logic we are not passing Start Date and End Date
			// startDate = new GlideDateTime().getValue();
			// endDate = new GlideDateTime().getValue();
		}
		ScheduleLoader loader = new ScheduleLoader();
		loader.checkAndGetSchedule(scheduleId, timeZone);
	}
	
	public void jsFunction_invalidateSchedules() {
		LogHelper.logMsg(this.getClass().getName(), "Into jsFunction_invalidateSchedules");
		PTScheduleCache.invalidateSchedules();
	}
	
	public String jsFunction_recalculateRelation(Object relationRecord, String topTaskId, String operation) {
		LogHelper.logMsg(this.getClass().getName(), "Into jsFunction_recalculateRelation");
		GlideRecord relationGR = (GlideRecord)relationRecord;
		String relationSysId = relationGR.getValue(SYS_ID);
		String parentSysId = relationGR.getValue(PARENT);
		String childSysId = relationGR.getValue(CHILD);
		try {
			if( PlannedTaskValueValidator.isValidString(relationSysId) && 
					PlannedTaskValueValidator.isValidString(operation) &&  
					PlannedTaskValueValidator.isValidString(parentSysId)) {
				PlannedTaskStructure structure = loadStructure(topTaskId);
				PlannedTaskRelation relation = structure.getRelation(relationSysId);
				if( null != relation ) {
					if( INSERT_OPERATION.equals(operation) || UPDATE_OPERATION.equals(operation)) {
						// Invoked in a After BR
						relation.setLag(relationGR.getValue(LAG));
						relation.setParent(parentSysId);
						relation.setChild(childSysId);
						List<PlannedTask> subTreeNodes = structure.getSubTreeNodes(relation.getChild());
						for (PlannedTask subTreeNode : subTreeNodes) {
							subTreeNode.setDirty(true);
						}
					} else {
						// Invoked By Before BR
						relation.setDeleted(true);
						structure.markRelationForDelete(relation.getSysId());
						if(PlannedTaskValueValidator.isValidObject(relation.getOrigSysId()) && 
								PlannedTaskRelationDBService.isDownStreamRelation(relation.getSysId()) &&
								!PlannedTaskRelationDBService.hasMoreThanOneOutgoingRelationFrom(relation.getParent())) {	
							structure.markTaskForDelete(relation.getParent());
							taskEngine.validateWbsForDelete(structure, relation.getParent());
						}
						GraphChangeManager changeManager = new GraphChangeManager(structure, TimeZoneHelper.getUserTimezone());
						changeManager.resetRelation();
					}
				}
				this.taskEngine.recalculate(structure, TimeZoneHelper.getUserTimezone(), this.constraints);
				this.getDataStore(false).save(structure);
				LogHelper.logMsg(this.getClass().getName(), "Finished jsFunction_recalculateRelation");
				return this.returnJsonData(null, null, structure);
			}
		} catch ( Exception e) {
			GlideSystem.getGlideSystem().jsFunction_logError("Unable to Recalculate Realtion : " + e.getMessage(), 
					PlannedTaskAPI.class.getName());
		}
		return ""; 
	}
	
	// Call After Insert BR - Set TopTask, SubTreeRoot, Level, WBS
	public String jsFunction_recalculateTask(Object record, boolean saveProject, String dirtyTaskIds) {
		return jsFunction_recalculateTaskWithPreviousGr(record, saveProject, dirtyTaskIds, null);
	}
	
	public String jsFunction_recalculateTaskWithPreviousGr(Object record, boolean saveProject, 
			String dirtyTaskIds, Object previousGlideRecord) {
		GlideRecord taskRecord = (GlideRecord) record;
		String sysId = taskRecord.getValue(SYS_ID);
		String operation = taskRecord.jsFunction_operation();
		LogHelper.logInfo(this.getClass().getName(), "PPM Into jsFunction_recalculateTask "+sysId);
		PlannedTaskStructure projectGraph = null;
		try {
			if (PlannedTaskValueValidator.isValidString(sysId)
					&& PlannedTaskValueValidator.isValidString(operation)) {
			    projectGraph = loadStructure(sysId, taskRecord);
			    // Mark the dirty tasks here
			    markDirtyTasks(dirtyTaskIds, projectGraph);
			    applyPreviousValuesToStructure(previousGlideRecord, projectGraph);
				if (INSERT_OPERATION.equals(operation) || taskRecord.isNewRecord()) { 
					PlannedTask task = projectGraph.getTask(sysId);
					task.setStartDate(task.getScheduleStartDate()); // VERIFY: re-initialize start date
					task.setDirty(true); 
					this.taskEngine.recalculate(projectGraph, TimeZoneHelper.getUserTimezone(), 
							this.constraints);
					if( saveProject) 
						this.getDataStore(false).save(projectGraph);

				} else  if( UPDATE_OPERATION.equals(operation) ) {
					Map<String, Object> recordMap = getChangeMapFromRecord(taskRecord);
					Map<String, Object> actionMap = new HashMap<String, Object>();
					actionMap.put(SYSPARM_NAME, UPDATE_TASK);
					String changeProperties = "";
					for ( String property : recordMap.keySet() ) 
						changeProperties += property + ",";
					changeProperties = changeProperties.substring(0, changeProperties.length() - 1);
					actionMap.put(SYSPARM_PROPERTY, changeProperties);
					actionMap.put(SYSPARM_TASK, recordMap);
					ColumnMaps maps = new ColumnMaps(taskRecord);
					Action action = Action.buildAction(actionMap, maps);
					this.performAction(projectGraph, action, maps, false);
				} else if( DELETE_OPERATION.equals(operation)) {
					PlannedTask node = projectGraph.getTask(sysId);
					PlannedTask parentNode = projectGraph.getTask(node.getParent());
					if( null != parentNode ) {
						parentNode.setDirty(true);
					}
					projectGraph.markTaskForDelete(sysId);
					projectGraph = this.taskEngine.deleteWBS(projectGraph);
					this.taskEngine.recalculate(projectGraph, TimeZoneHelper.getUserTimezone(), 
							this.constraints);
					if( saveProject) 
						this.getDataStore(false).save(projectGraph);
				}
				return this.returnJsonData(taskRecord, dirtyTaskIds, projectGraph);
			}
		} catch ( Exception e) {
			GlideSystem.getGlideSystem().jsFunction_logError("PPM Unable to Recalculate Task : " + sysId+ " " +e.getMessage(), PlannedTaskAPI.class.getName());
		}
		return ""; // Type safe
	}

	// TODO: Find a better way to apply changed previous date(s) to structure
	private void applyPreviousValuesToStructure(Object previousGlideRecord,
			PlannedTaskStructure projectGraph) {
		if(PlannedTaskValueValidator.isValidObject(previousGlideRecord)) {
			GlideRecord previousGr = (GlideRecord) previousGlideRecord;
			String sysId = previousGr.getValue(PlannedTaskFieldConstants.SYS_ID);
			PlannedTask task = projectGraph.getTask(sysId);
			if(null != task) {
				task.setPreviousStartDate(DateTimeHelper.getGlideDateTime(
							previousGr.getValue(PlannedTaskFieldConstants.START_DATE)));
				task.setPreviousEndDate(DateTimeHelper.getGlideDateTime(
							previousGr.getValue(PlannedTaskFieldConstants.END_DATE)));
			}
		}
	}

	private String returnJsonData(GlideRecord taskRecord, String dirtyTaskIds, PlannedTaskStructure projectGraph) {
		PlannedTaskJSONData jsonData = new PlannedTaskJSONData();
		List<PlannedTask> dirtyTasks = projectGraph.getDirtyTasks();
		for (PlannedTask plannedTask : dirtyTasks) {
			JSONObject jsonTask = new JSONObject();
			PlannedTaskToJsonObjectConverter.convertToJsonObject(plannedTask, jsonTask);
			jsonData.addTask(jsonTask);
		}
		List<PlannedTaskRelation> dirtyRelations = projectGraph.getDirtyRelations();
		for (PlannedTaskRelation relation : dirtyRelations) {
			JSONObject jsonTask = new JSONObject();
			PlannedTaskRelationToJsonConverter.convertToJsonObject(relation, jsonTask);
			jsonData.addRelation(jsonTask);
		}
		return JSONUtil.stringify(jsonData);
	}

	private void markDirtyTasks(String dirtyTaskIds,
			PlannedTaskStructure projectGraph) {
		if(PlannedTaskValueValidator.isValidString(dirtyTaskIds) && projectGraph.hasTasks()) {
			String[] sysIds = dirtyTaskIds.split(",");
			if(null != sysIds) {
				for (String taskSysId : sysIds) {
					if(PlannedTaskValueValidator.isValidString(taskSysId)) {
						PlannedTask task = projectGraph.getTask(taskSysId);
						if(null != taskSysId) {
							task.setDirty(true);
						}
					}
				}
			}
		}
	}

	public void jsFunction_setTranslatedMessages(String msgJson) {
		Map<String, String> msgStack = JSONUtil.parseMap(msgJson);
		this.msgs = new SeededMessages(msgStack);
	}
	
	public String jsFunction_ganttData(final String entity, final String sysId, Object queryConstraint) {
//		if ( entity.equals("release") ) {
//			ReleaseBuilder releaseBuilder = new ReleaseBuilder(sysId);
//			PlannedTaskStructure plannedTaskStructure = releaseBuilder.build();
//			TimelineViewModel tmv = new TimelineViewModel(plannedTaskStructure);
//			return JSONUtil.stringify(tmv);	
//		}
		return this.ganttData(entity, sysId, queryConstraint);
	}
	
	public String jsFunction_multiTaskGanttData(final String table, final String sysIds) {
		GlideRecord gr = new GlideRecord(table);
		gr.addQuery("sys_id", "IN", sysIds);
		gr.query();
		PlannedTaskStructure structure = new PlannedTaskStructure();
		IPlannedTaskFactory factory = new PlannedTaskFactory();
		while(gr.next()) {
			PlannedTask project = factory.create(gr);
			PlannedTaskRecordDecorator recordDecorator = new PlannedTaskRecordDecorator(gr);
			if(recordDecorator.hasChildren()) 
				project.setExpand(true);
			structure.addTask(project);
		}
		return this.stringifyStructure(structure, null);
	}
	
	public String jsFunction_multiTaskGanttDataV2(final String table, final String sysIds) {
    	ConsoleMyGanttDataLoader loader = new ConsoleMyGanttDataLoader(new ConsoleGanttFormatDataLoader(new ConsoleDataLoader(table,"my_gantt")));
    	IPlannedTaskData data = loader.loadTopTasks(table, sysIds);
    	return JSONUtil.stringify(data);
	}
	
	public String jsFunction_applyChanges(String entityId, String sysClassName, String previousOperationsJSON, String currentOperationJSON, boolean isSave) {
		return this.applyChanges(entityId, sysClassName, previousOperationsJSON, currentOperationJSON);
	}
	
	
	// Planning Console Methods
	private String ganttData(final String entity, final String sysId, Object qConstraint) {
        if (PlannedTaskValueValidator.isValidString(sysId)) {
            IPlannedTaskStructureBuilder builder = null;
            if ("pm_project".equalsIgnoreCase(entity)) {
                PlannedTaskEngine taskEngine = new PlannedTaskEngine();
                PlannedTaskStructure structure = loadStructure(sysId, entity);
                taskEngine.validateWbs(structure);
                return this.stringifyStructure(structure, null);
            }
            else if ("rm_release".equalsIgnoreCase(entity)) {
                PlannedTaskStructure structure = loadStructure(sysId, entity);
                return this.stringifyStructure(structure, null);
            }
            else if ("pm_program".equalsIgnoreCase(entity)) 
                builder = new ProgramBuilder(sysId);
            else if ("pm_portfolio".equalsIgnoreCase(entity)) 
                builder = new PortfolioBuilder(sysId);
            
            if (builder != null) {
            	StopWatch stopWatch = new StopWatch();
            	stopWatch.start();
            	stopWatch.log("Start of Conversion of Structure Gantt Data...");
            	QueryConstraint queryConstraint = (QueryConstraint) qConstraint;
                PlannedTaskStructure plannedTaskStructure = builder.build(queryConstraint);
                TimelineViewModel tmv = new TimelineViewModel(plannedTaskStructure, queryConstraint);
                stopWatch.log("End of Conversion of Structure Gantt Data...");
                stopWatch.stop();
                return JSONUtil.stringify(tmv);
            }
        }
		return null;
	}
	
	public String jsFunction_ganttDataV2(final String sysClass, final String context, 
			final String sysId, Object queryConstraint, boolean showCriticalPath) {
		return this.jsonGanttData(sysClass, context, sysId, queryConstraint, showCriticalPath);
	}
	
	private String jsonGanttData(final String sysClass, String context, 
			final String sysId, Object queryConstraint, boolean showCriticalPath) {
		PlannedTaskGanttData ganttData = new PlannedTaskGanttData();
		if (PlannedTaskValueValidator.isValidString(sysId)) {
        	if ( !PlannedTaskValueValidator.isValidString(context) )
        		context = "default";
        	StopWatch stopWatch = new StopWatch();
        	stopWatch.start();
        	LogHelper.logInfo(this.getClassName(), "PPM Into jsonGanttData "+sysId+" "+sysClass);
        	if(RecordAccessHelper.canReadRecord(sysClass, sysId)) {
        		ConsoleDataLoader loader = new ConsoleDataLoader(sysClass, context);//SimpleHelper.fallbackEntity(entity));
            	ConsoleGanttFormatDataLoader ganttLoader = new ConsoleGanttFormatDataLoader(loader);
            	PlannedTaskJSONData jsonData = (PlannedTaskJSONData) ganttLoader.loadDataWithoutDecorate(
            			sysClass, sysId, ((QueryConstraint)queryConstraint));
            	if(showCriticalPath) {
            		PlannedTaskStructure structure = jsonData.toPlannedTaskStructure();
            		ganttData = (PlannedTaskGanttData) ganttLoader.decorateToGanttData(sysClass, jsonData);
            		if(null != structure && structure.hasTasks()) {
            			try {
            				CriticalPathProcessor criticalPathProcessor = new CriticalPathProcessor();
            				criticalPathProcessor.criticalPath(structure);
            				PlannedTaskJsonStructureComparator comparator = new PlannedTaskJsonStructureComparator();
                    		comparator.markCritical(ganttData, structure);
            			} catch(PTScheduleException exception) {
            				ganttData.setMessage(PlannedTaskException.UNABLE_TO_CALCULATE_CRITICAL_PATH);
            			}
            		}
            	} else {
            		ganttData = (PlannedTaskGanttData) ganttLoader.decorateToGanttData(sysClass, jsonData);
            	}
        	} else {
        		ganttData.setStatus(PlannedTaskFieldConstants.STATUS_ERROR);
        		ganttData.setMessage(PlannedTaskFieldConstants.STATUS_ERROR);
        	}
        	stopWatch.stop();
        	LogHelper.logInfo(this.getClassName(), "PPM Load jsonGanttData completed! "+sysId+" "+sysClass+" "+LogHelper.longToTime(stopWatch.getTime()));
        	return JSONUtil.stringify(ganttData);
        }
		return JSONUtil.stringify(new PlannedTaskGanttData()); // fail safe for client
	}
	
	private String stringifyStructure(PlannedTaskStructure structure, Collection<PlannedTaskRelation> relations) {
		ProjectView projectView = new ProjectView();
		projectView.processGraph(structure);
		if( null == relations)
			projectView.resetNodeDependencies(structure.getRelations());
		else 
			projectView.resetNodeDependencies(relations);
		projectView.setStatus(structure.getStatus());
		projectView.setMessage(structure.getStatusMessage());
		return this.stringify(projectView);
	}
	
	private String stringify(Object object) {
		return JSONUtil.stringify(object);
	}
	
	private String applyChanges(String entityId, String sysClassName, String previousOperationsJSON, String currentOperationJSON) {
		List<Object> previousOperations = new ArrayList<Object>();
		if( PlannedTaskValueValidator.isValidString(previousOperationsJSON)) {
			String previousOpsJSON = previousOperationsJSON;
			if( !previousOpsJSON.startsWith("[")) 
				previousOpsJSON = "[" + previousOpsJSON + "]";
			previousOperations = JSONUtil.parseList(previousOpsJSON);
		}
		
		Map<String, Object> actionMap = new HashMap<String, Object>();
		if( PlannedTaskValueValidator.isValidString(currentOperationJSON)) {
			actionMap = JSONUtil.parseMap(currentOperationJSON);
		}
		return this.applyChanges(entityId, sysClassName, previousOperations, actionMap);
	}
	
	private String applyChanges(String entityId, String sysClassName, List<Object> previousOperations, Map<String, Object> actionMap) {
		StopWatch s = new StopWatch();
		s.start();
		LogHelper.logInfo(PlannedTaskAPI.class.getName(), "PPM Into applyChange "+entityId);
		PlannedTaskStructure structure = null;
		PlannedTaskJSONData jsonData = null;
		Console console = null;
		boolean fireBRs = false;
		String entity = sysClassName;
		try {
			ConsoleConfigurationLoader configLoader = new ConsoleConfigurationLoader();
	    	console = configLoader.getConsole(entity, "default", true);
			ColumnMaps maps = new ColumnMaps(console);
				
			List<Action> actions = ActionBuilder.buildActions(previousOperations, actionMap, maps);
			
			
			Loader l = LoadStrategy.getLoader(console, actions);
			jsonData = l.loadData(console, entity, entityId);
			structure = jsonData.toPlannedTaskStructure();
			
			FireBRStrategy strategy = new FireBRStrategy(jsonData, console);
			fireBRs = strategy.shouldBRsBeFired(actions);
			ProjectAction projectAction = ProjectActionStrategy.actionStrategy(console, actions, fireBRs);
			for (Action action: actions) {
				//action = Action.buildAction(eachAction, maps);
				//lastAction = action;
				AccessHelper.validateAction(action);
				// Don't save the structure till all actions are applied
				structure = this.performAction(structure, action, maps, projectAction);
			}
			
			projectAction.performSave(structure);
			structure = projectAction.performReload(structure);
			
			if(fireBRs) {
            	jsonData.setFullReload(true);
            	// The error might only occur if FireBR is set to true and Error Msgs are available in the session
            	if(null != GlideSession.get().getNamedMessages(GlideSession.ERROR) && 
            			GlideSession.get().getNamedMessages(GlideSession.ERROR).size() > 0) {
            		List<Object> errorMessages = GlideSession.get().getNamedMessages(GlideSession.ERROR);
            		String errorMessage = errorMessages.get(0).toString();
            		GlideSession.get().flushMessages(GlideSession.ERROR);
            		jsonData.setFullReload(false);
            		throw new PlannedTaskException(errorMessage);
            	}
            } else {
            	 PlannedTaskJsonStructureComparator comparator = new PlannedTaskJsonStructureComparator();
            	 comparator.compareAndMark(jsonData, structure, console);
            }

            
            ConsoleGanttFormatDataLoader ganttDataLoader = new ConsoleGanttFormatDataLoader(console);
            ganttDataLoader.setOriginalTaskStructure(structure);
//            if(null != actionMap.get(PlannedTaskFieldConstants.SYSPARM_TASK) && actionMap.get(PlannedTaskFieldConstants.SYSPARM_TASK) instanceof String)
//            	actionMap.put(PlannedTaskFieldConstants.SYSPARM_TASK, JSONUtil.parseMap(String.valueOf(actionMap.get(PlannedTaskFieldConstants.SYSPARM_TASK))));
            ganttDataLoader.setOriginalTask(actions.get(actions.size() - 1).getTaskMap());
            PlannedTaskGanttData ganttData = (PlannedTaskGanttData) ganttDataLoader.decorateToGanttData(entity, jsonData);
            for (JSONObject task : ganttData.getTasks()) {
				try {
					JSONUtil.stringify(task);
				} catch (Exception e) {
					LogHelper.logInfo(PlannedTaskAPI.class.getName(), "PPM applyChange exception in Stringify "+e.getMessage());
					System.out.println(task.get("id"));
				}
			}
            String serializedGanttData = JSONUtil.stringify(ganttData);
            s.stop();
    		LogHelper.logInfo(PlannedTaskAPI.class.getName(), "PPM Completed applyChange "+entityId + " in "+LogHelper.longToTime(s.getTime()));
            return serializedGanttData;
          //return this.processGanttData(structure);
		} catch ( PlannedTaskException e) {
    		LogHelper.logInfo(PlannedTaskAPI.class.getName(), "PPM PlannedTaskException in applyChange "+entityId);
			return this.stringify(this.getErrorGantt(entity, jsonData, console, e));
		} catch (Exception e) {
    		LogHelper.logInfo(PlannedTaskAPI.class.getName(), "PPM Exception in applyChange "+entityId);
			e.printStackTrace();
		}
		return "";
	}

	private Object getErrorGantt(String entity, PlannedTaskJSONData jsonData, Console console,
			PlannedTaskException e) {
		PlannedTaskGanttData ganttData = null;
		if(null == jsonData) {
			ganttData = new PlannedTaskGanttData();
		} else {
			ConsoleGanttFormatDataLoader ganttDataLoader = new ConsoleGanttFormatDataLoader(console);
	        ganttData = (PlannedTaskGanttData) ganttDataLoader.decorateToGanttData(entity, jsonData);
		}
        ganttData.setStatus(PlannedTaskException.ERROR);
		ganttData.setMessage(this.msgs.getTranslatedMessage(e.getMessage()));
        return ganttData;
	}

	private Object getErrorGantt(PlannedTaskStructure structure,
			PlannedTaskException e) {
		ProjectView ganttData = new ProjectView();
		ganttData.setStatus(PlannedTaskException.ERROR);
		ganttData.setMessage(this.msgs.getTranslatedMessage(e.getMessage()));
		if ( structure != null )
			ganttData.processGraph(structure);
		return ganttData;
	}

	private String processGanttData(PlannedTaskStructure structure) {
		// Changed to return dirty nodes and relations
		Collection<PlannedTask> tasks = structure.getTasks();
		PlannedTaskStructure dirtyNodeGraph = new PlannedTaskStructure();
		for (PlannedTask task : tasks) {
			if( task.isDirty() || task.isDeleted() || task.isCritical()) {
				dirtyNodeGraph.addTask(task);
			}
		}
		Collection<PlannedTaskRelation> relations = structure.getRelations();
		for (PlannedTaskRelation relation : relations) {
			if( relation.isDirty() || relation.isDeleted()) {
				//dirtyNodeGraph.getRelations().add(relation);
				dirtyNodeGraph.addRelation(relation);
				// For SF Relation: Explicitly add the Parent Node
				if(RelationCheckHelper.isStartToFinishRelation(relation.getSubType())) {
					String nodeId = relation.getChild();
					if(null == dirtyNodeGraph.getTask(nodeId)) {
						PlannedTask task = structure.getTask(nodeId);
						task.setDirty(true);
						dirtyNodeGraph.addTask(task);
					}
				}
				relation.setSubType(PlannedTaskRelation.deriveGanttSubType(relation.getSubType()));
			}
		}
		dirtyNodeGraph.setStatus(structure.getStatus());
		dirtyNodeGraph.setStatusMessage(structure.getStatusMessage());
		return this.stringifyStructure(dirtyNodeGraph, relations);
	}
	
	// New Method to be called on After Delete
	public String jsFunction_deleteTaskAndRecalculate(Object record, boolean saveProject) {
		String sysId = "";
		try {
			GlideRecord taskRecord = (GlideRecord) record;
			sysId = taskRecord.getValue(SYS_ID);
			StopWatch s = new StopWatch();
			LogHelper.logInfo(this.getClass().getName(), "PPM jsFunction_deleteTaskAndRecalculate "+sysId);
			String operation = taskRecord.jsFunction_operation();
			LogHelper.logMsg(this.getClass().getName(), "operation: " + operation);
			PlannedTaskStructure projectGraph = this.loadStructure(sysId);
			PlannedTask nodeToBeDeleted = projectGraph.getTask(sysId);
			nodeToBeDeleted.setDeleted(true);
			this.taskEngine.validateWbsForDelete(projectGraph,sysId);
			
			List<PlannedTask> subTreeNodes = projectGraph.getSubTreeNodes(nodeToBeDeleted.getSysId());
			List<String> subTreeNodeIds = new ArrayList<String>();
			for (PlannedTask subTreeNode : subTreeNodes) {
				subTreeNodeIds.add(subTreeNode.getSysId());
				projectGraph.removeTaskFromStructure(subTreeNode.getSysId());
			}
			PlannedTaskDBService.deleteFromSysIds(subTreeNodeIds);
			
			Collection<PlannedTaskRelation> relations = projectGraph.getRelations();
			List<String> deletedRelationsId = new ArrayList<String>();
			List<PlannedTaskRelation> deletedRelations = new ArrayList<PlannedTaskRelation>();
			for (PlannedTaskRelation relation : relations) {
				if(subTreeNodeIds.contains(relation.getParent()) || 
						subTreeNodeIds.contains(relation.getChild())) {
					deletedRelationsId.add(relation.getSysId());
					deletedRelations.add(relation);
					projectGraph.removeRelationFromStructure(relation.getSysId());
					// Check the relation parent/child is shadow
					PlannedTask parent = projectGraph.getTask(relation.getParent());
					if(null != parent && parent.isShadow() && !parent.isDeleted()) {
						GlideRecord gr = PlannedTaskRelationDBService.fetchAllRelations(new String[]{parent.getSysId()});
						if(gr.getRowCount() == 0)
							parent.setDeleted(true);
					}
					PlannedTask child = projectGraph.getTask(relation.getChild());
					if(null != child && child.isShadow() && !child.isDeleted()) {
						GlideRecord gr = PlannedTaskRelationDBService.fetchAllRelations(new String[]{child.getSysId()});
						if(gr.getRowCount() == 0)
							child.setDeleted(true);
					}
				}
			}
			PlannedTaskRelationDBService.deleteFromSysIds(deletedRelationsId);
			
			// Set the Parent of this task as Dirty
			PlannedTask parentNode = projectGraph.getTask(nodeToBeDeleted.getParent());
			if( null != parentNode) {
				parentNode.setDirty(true);
				if(projectGraph.getActiveChildren(parentNode.getSysId()).size() == 0) {
					parentNode.setRollup(false);
				}
			}
			// nodeToBeDeleted.setDeleted(false); // Reset it as It should call the Delete Recursively
			// Recalculate the Project
			this.taskEngine.recalculate(projectGraph, TimeZoneHelper.getUserTimezone(), 
					this.constraints);
			// Validate WBS - Use case where Pred project - Pred task delete with Interproject relation
			WbsValidator wbsValidator = new WbsValidator();
			wbsValidator.processGraphForValidation(projectGraph);
			// Save the Project
			if(saveProject && projectGraph.getTasks().size() > 0) {
				this.getDataStore(false).save(projectGraph);
			}
			s.stop();
			LogHelper.logInfo(this.getClass().getName(), "PPM jsFunction_deleteTaskAndRecalculate completed "+sysId+ " in "+LogHelper.longToTime(s.getTime()));
			return this.returnDeletedTasksAndRelation(subTreeNodes, deletedRelations);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			GlideSystem.getGlideSystem().jsFunction_logError("PPM Unable to Recalculate Delete of Task : " + sysId + " "+ e.getMessage(), this.getClassName());
		}
		return "";
	}
	
	private String returnDeletedTasksAndRelation( List<PlannedTask> deletedTasks,
			List<PlannedTaskRelation> deletedRelations) {
		PlannedTaskJSONData jsonData = new PlannedTaskJSONData();
		if(null != deletedTasks) {
			for (PlannedTask task : deletedTasks) {
				JSONObject jsonTask = new JSONObject();
				PlannedTaskToJsonObjectConverter.convertToJsonObject(task, jsonTask);
				jsonData.addTask(jsonTask);
			}
		}
		if(null != deletedRelations) {
			for (PlannedTaskRelation relation : deletedRelations) {
				JSONObject jsonRelation = new JSONObject();
				PlannedTaskToJsonObjectConverter.convertToJsonObject(relation, jsonRelation);
				jsonData.addRelation(jsonRelation);
			}
		}
		return JSONUtil.stringify(jsonData);
	}

	private void deleteNodeRecursively(PlannedTaskStructure projectGraph, String sysId) {
		PlannedTask node = projectGraph.getTask(sysId);
		// Delete the Leaf Nodes first and Recursively delete the Parent Nodes
		if( null != node) {
			List<PlannedTask> childNodes =  projectGraph.getChildren(sysId);
			if( childNodes.size() > 0) {
				Iterator<PlannedTask> childNodeIterator = childNodes.iterator();
				while(childNodeIterator.hasNext()) {
					PlannedTask childNode = childNodeIterator.next();
					this.deleteNodeRecursively(projectGraph, childNode.getSysId());
				}
			}
			this.deleteNodeAndRelation(projectGraph, node);
		}
	}

	private void deleteNodeAndRelation(PlannedTaskStructure projectGraph,
			PlannedTask node) {
		if( null != node ) {
			node.setDeleted(true);
		}
		// Delete the Relations
		List<PlannedTaskRelation> nodeRelations = projectGraph.getNodeRelations(node.getSysId());
		Iterator<PlannedTaskRelation> relationIterator = nodeRelations.iterator();
		while (relationIterator.hasNext()) {
			PlannedTaskRelation relation = (PlannedTaskRelation) relationIterator.next();
			relation.setDeleted(true);
		}
	}
	
	public String jsFunction_showCriticalPath(String projectSysId) {
		PlannedTaskStructure projectGraph = null;
		if( PlannedTaskValueValidator.isValidString(projectSysId) ) {
			projectGraph = this.loadStructure(projectSysId);
			CriticalPathProcessor criticalPathProcessor = new CriticalPathProcessor();
			PlannedTaskGanttData ganttData = new PlannedTaskGanttData();
			try {
			criticalPathProcessor.criticalPath(projectGraph);
			// return this.processGanttData(projectGraph);
			String entity = projectGraph.getRootNode().getSysClassName();
    		ConsoleDataLoader dataLoader = new ConsoleDataLoader(entity, "default"); // this is only triggered for project
    		ConsoleGanttFormatDataLoader ganttLoader = new ConsoleGanttFormatDataLoader(dataLoader);
			ganttData = (PlannedTaskGanttData) ganttLoader.loadData(SimpleHelper.fallbackEntity(entity), projectSysId, new QueryConstraint());
			PlannedTaskJsonStructureComparator comparator = new PlannedTaskJsonStructureComparator();
       	 	comparator.markCritical(ganttData, projectGraph);
			} catch (PTScheduleException e) {
				GlideSystem.getGlideSystem().jsFunction_logError("Unable to calculate critical path : " + e.getMessage(), 
						PlannedTaskAPI.class.getName());
			}
            return JSONUtil.stringify(ganttData);
		}
		return this.stringify(this.getErrorGantt(projectGraph, new PlannedTaskException(
				PlannedTaskException.UNABLE_TO_CALCULATE_CRITICAL_PATH)));
	}
	
	public void jsFunction_closeWholeProject(Object topTaskNode, Object taskKeyVals) {
		LogHelper.logMsg(this.getClass().getName(), "PPM Into Close whole project");
		HashMap<String, String> hashTaskKeyVals = GlideRhinoHelper.getMapFromRhino(taskKeyVals);
		GlideRecord taskRecord = (GlideRecord) topTaskNode;
		if(taskRecord.getValue(SYS_ID).equals(taskRecord.getValue(TOP_TASK))) {
			@SuppressWarnings("unused")
			int updateCount = 0;
			String work_start = null;
			GlideDateTime workEnd = null;
			GlideDateTime workStart = null;
			String workDuration = null;
			String scheduleId = taskRecord.getValue(SCHEDULE);
			PTSchedule schedule = this.getScheduleUtil(scheduleId, TimeZoneHelper.getUserTimezone());
			// Meant to be whole Project Update
			PlannedTaskRecordDecorator topTaskRecord = new PlannedTaskRecordDecorator(taskRecord);
			GlideRecord allTasks = topTaskRecord.allTasks(true);
			while(allTasks.next()) {
				if(taskRecord.getValue(SYS_ID).equals(allTasks.getValue(SYS_ID))){
					continue;
				}
				GlideRecord taskRec = new GlideRecord(allTasks.getValue(SYS_CLASS_NAME));
				taskRec.initialize();
				ElementDescriptor taskED = taskRec.getTableDescriptor().getElementDescriptor("state");
				String closedStates = taskED.getAttribute("close_states");
				if(allTasks.getValue(STATE) != null && ( closedStates.indexOf(allTasks.getValue(STATE)) != -1)){
					continue;
				}
				for (String key : hashTaskKeyVals.keySet()) {
					if(key.equals(WORK_END)){
						workEnd = new GlideDateTime();
						workEnd.setDisplayValue(hashTaskKeyVals.get(key));
						
						work_start = allTasks.getDisplayValue(WORK_START);
						workStart = new GlideDateTime();
						if(StringUtil.nil(work_start)){
							work_start = hashTaskKeyVals.get(key);
							workStart.setDisplayValue(work_start);
							allTasks.setValue("work_start", workStart.getValue());
						}else{
							workStart.setDisplayValue(work_start);
						}
						
						if(schedule != null){
							GlideDuration duration = schedule.calculateDuration(workStart, workEnd);
							float hoursPerDay = schedule.getHoursPerDayFromSchedule(workStart);
							GlideDuration durationDisplay = DurationHelper.convertToGlideDuration(hoursPerDay,
									String.valueOf(duration.getDayPart()), 
									duration.getByFormat("HH"), duration.getByFormat("mm")); 
							allTasks.setValue(WORK_DURATION, durationDisplay.getValue());
						}else{
							workDuration = GlideSystemDateUtil.jsFunction_dateDiff(workStart.getDisplayValue(), workEnd.getDisplayValue(), false);
							allTasks.setValue(WORK_DURATION, workDuration);
						}
						allTasks.setValue(key, workEnd.getValue());
					}else if(key.equals(STATE)){
					    if(closedStates.indexOf(hashTaskKeyVals.get(key)) != -1){
					        allTasks.setValue(key, hashTaskKeyVals.get(key));
					    }else{
					        String defualtClosedState = taskED.getAttribute("default_close_state"); 
					        allTasks.setValue(key, defualtClosedState);
					    }
					}else
						allTasks.setValue(key, hashTaskKeyVals.get(key));
				}
				allTasks.setWorkflow(false);
				allTasks.update();
				updateCount++;
			}
			try {
				new PlannedTaskAPI().fullRecalculate(taskRecord.getValue(SYS_ID));
			} catch( Exception e) {
				Log.error("Unable to Recalculate Closed Project");
			}
			LogHelper.logMsg(this.getClass().getName(), " Close whole project complete!");
		}
	}
	
	// Directly return the State of Sub Tree ASAP Leaf Nodes
	public String[]  jsFunction_getLeafNodesForStateChange(String taskSysId, boolean leafOfSuccessors) {
		if( PlannedTaskValueValidator.isValidString(taskSysId)) {
			GlideRecord taskRecord = new GlideRecord(PlannedTaskTableConstants.PLANNED_TASK);
			if( taskRecord.get(taskSysId)) {
				PlannedTaskStructure structure = this.loadStructure(taskSysId, true);
				EngineGraph graph = new EngineGraph();
				graph.cloneFromStructure(structure);
				EngineTask node = graph.getTask(taskSysId);
				List<String> subTreeLeafNodesSysIdes = new ArrayList<String>();
				if( !leafOfSuccessors ) { // Get Leaf Nodes of this node
					DAGConverter dagConverter = new DAGConverter(graph);
					dagConverter.transformTreeToGraph();
					List<EngineTask> subTreeLeafNodes = graph.getSubTreeLeafNodes(node);
					for (EngineTask subTreeLeafNode : subTreeLeafNodes) {
						// Is ASAP, Active and Predecessors Processed
						PlannedTask subTreeLeafTask = structure.getTask(subTreeLeafNode.getSysId());
						if(ASAP_TIME_CONSTRAINT.equals(subTreeLeafNode.getTimeConstraint()) &&
								subTreeLeafTask.isActive() && this.isPredecessorsProcessed(subTreeLeafNode, graph, node) &&
								!taskSysId.equals(subTreeLeafNode.getSysId())) {
							subTreeLeafNodesSysIdes.add(subTreeLeafNode.getSysId());
						} else if(CALCULATION_TYPE_MANUAL.equals(structure.getRootNode().getCalculationType()) && 
								subTreeLeafTask.isActive() && this.isPredecessorsProcessed(subTreeLeafNode, graph, node) &&
								!taskSysId.equals(subTreeLeafNode.getSysId()) && subTreeLeafTask.getStartDate().compareTo(structure.getRootNode().getStartDate()) <= 0) {
							subTreeLeafNodesSysIdes.add(subTreeLeafNode.getSysId());
						}
					}
					return subTreeLeafNodesSysIdes.toArray(new String[subTreeLeafNodesSysIdes.size()]);
				} else { // fetch the Leaf of Successors Node
					List<EngineRelation> successors = node.getSuccessors();
					for (EngineRelation successor : successors) {
						if(RelationCheckHelper.isStartToFinishRelation(successor.getSubType())){
							continue; // skip the SF Relations as they are actual successor
						}
						EngineTask successorNode = graph.getTask(successor.getChild());
						PlannedTask successorTask = structure.getTask(successorNode.getSysId());
						if(successorNode.getChildren().size() > 0) {
							// Get the Leaf of the Successors
							List<EngineTask> subTreeLeafNodes = graph.getSubTreeLeafNodes(successorNode);
							for (EngineTask subTreeLeafNode : subTreeLeafNodes) {
								// Is ASAP, Active and Predecessors Processed
								PlannedTask subTreeLeafTask = structure.getTask(subTreeLeafNode.getSysId());
								if(ASAP_TIME_CONSTRAINT.equals(subTreeLeafNode.getTimeConstraint()) &&
										subTreeLeafTask.isActive() && this.isPredecessorsProcessed(subTreeLeafNode, graph, node) )
									subTreeLeafNodesSysIdes.add(subTreeLeafNode.getSysId());
							}
						} else {
							if(ASAP_TIME_CONSTRAINT.equals(successorNode.getTimeConstraint()) &&
									successorTask.isActive() 
									// Currently its Called By Before BR, Where Active and End Date of Predecessors is not filled
									&& this.isPredecessorsProcessed(successorNode, graph, node)
									)
								subTreeLeafNodesSysIdes.add(successorNode.getSysId());
						}
					}
					return subTreeLeafNodesSysIdes.toArray(new String[subTreeLeafNodesSysIdes.size()]);
				}
			}
		}
		return new String[0];
	}
	
	private boolean isPredecessorsProcessed(EngineTask subTreeLeafNode, EngineGraph graph, 
			EngineTask node) {
		if( subTreeLeafNode.getPredecessors().size() > 0) {
			for(EngineRelation predecessor: subTreeLeafNode.getPredecessors()) {
				EngineTask predecessorNode = graph.getTask(predecessor.getParent());
				if( null != predecessorNode &&  !node.getSysId().equals(predecessorNode.getSysId()) )
						if(/*predecessorNode.isActive() || */
								null == predecessorNode.getWorkEndDate()) 
							return false;
			}
		}
		return true;
	}
	
	public GlideRecord jsFunction_getDescendants(Object record, boolean inclusive) {
		GlideRecord taskRecord = (GlideRecord) record;
		if( null != record && taskRecord.instanceOf(PlannedTaskTableConstants.PLANNED_TASK)) {
			String topTaskId = taskRecord.getValue(TOP_TASK);
			if(PlannedTaskValueValidator.isValidString(topTaskId)) {
				// Check If the Sys Id is the Top Task Id
				if( topTaskId.equals(taskRecord.getValue(SYS_ID))) {
					GlideRecord topTaskRecord = new GlideRecord(PlannedTaskTableConstants.PLANNED_TASK);
					topTaskRecord.addQuery(TOP_TASK, topTaskId);
					topTaskRecord.addNullQuery(PlannedTaskFieldConstants.ORIG_SYS_ID);
					if(!inclusive) {
						topTaskRecord.addQuery(SYS_ID, "!=", topTaskId);
					}
					topTaskRecord.query();
					return topTaskRecord;
				}
				else { // Get the Children of the this task
					PlannedTaskStructure graph;
					graph = this.loadStructure(topTaskId, true);
					List<PlannedTask> subTreeNodes = graph.getSubTreeNodes(taskRecord.getValue(SYS_ID));
					List<String> subTreeNodesSysIdes = new ArrayList<String>();
					for (PlannedTask subTreeNode : subTreeNodes) {
						subTreeNodesSysIdes.add(subTreeNode.getSysId());
					}
					if( subTreeNodesSysIdes.size() > 0 ) {
						GlideRecord parentRecord = new GlideRecord(PlannedTaskTableConstants.PLANNED_TASK);
						parentRecord.addQuery(SYS_ID, "IN", StringUtil.join(subTreeNodesSysIdes, ","));
						if(!inclusive) {
							parentRecord.addQuery(SYS_ID, "!=", topTaskId);
						}
						parentRecord.query();
						return parentRecord;
					}
				}
			}
		}
		return null;
	}
	
	public String jsFunction_copyProject(String taskSysId, String startDate, String projectName, Object resetFields, Object defaultFields) {
		LogHelper.logMsg(this.getClass().getName(), "Into jsFunction_copyProject"); 
		try {
			return (new ProjectCopier()).copyProject(taskSysId, startDate, projectName, resetFields, defaultFields);
		} catch ( Exception e) {
			GlideSystem.getGlideSystem().jsFunction_logError("Unable to Update the Realtion : " + e.getMessage(), this.getClassName());
		}
		return null;
	}
	
	public String  jsFunction_copyTask(String taskSysId, String parentSysId, String shortDescription) {
		LogHelper.logMsg(this.getClass().getName(), "Into jsFunction_copyTask"); 
		try {
			return (new ProjectCopier()).copyTask(taskSysId, parentSysId, shortDescription);
		} catch ( Exception e) {
			GlideSystem.getGlideSystem().jsFunction_logError("Unable to Update the Realtion : " + e.getMessage(), this.getClassName());
		}
		return parentSysId;
	}
	
	public void jsFunction_fullRecalculate(String sysId) {
		try {
			if(PlannedTaskValueValidator.isValidString(sysId)) {
				this.fullRecalculate(sysId);
			}
		} catch (Exception e) {
			Log.info("Unable to Full Recalculate: " + e.getMessage());
		}
	}
	
	public GlideDateTime jsFunction_deriveDateFromOffset(String date, String offset) {
		GlideDateTime dateTime = DateTimeHelper.getGlideDateTime(date);
		return DateTimeHelper.glideDateTimeWithOffset(Long.parseLong(String.valueOf(dateTime.getNumericValue())), Integer.parseInt(offset));
	}
	
	public void jsFunction_setConstraints(RecalculationConstraint recalculationConstraint) {
		this.constraints = recalculationConstraint;
	}
	
	// This a base configuration call, the call is handled by the Meta-data Script Include
	public String jsFunction_entityMetadata(String sysClass, String context) {
		if ( !PlannedTaskValueValidator.isValidString(context) )
			context = "default";
		ConsoleConfigurationLoader configurationLoader = new ConsoleConfigurationLoader();
		Console console = configurationLoader.getConsole(sysClass, context, true);
		ConsoleMetadataGenerator generator = new ConsoleMetadataGenerator(console);
		List<ConsoleMetadata> columnMetadata = new ArrayList<ConsoleMetadata>();
		if(null != console) {
			columnMetadata = generator.generateMetadata();
		}
		return (new JSONUtil()).toJson(columnMetadata);
	}
	
	// TODO: Ideally check the explicit and feed it, Let the Script Include or Business rule take care of it
	public void jsFunction_updateRank(String tableName, 
			String filterQuery, String rankColumn, Object currentRecord) throws PlannedTaskException {
		if(PlannedTaskValueValidator.isValidString(tableName) && PlannedTaskValueValidator.isValidString(filterQuery) && 
		PlannedTaskValueValidator.isValidString(rankColumn) && null != currentRecord) {
			this.updateRank(tableName, filterQuery, rankColumn, (GlideRecord)currentRecord);
		}
	}
	
	private void updateRank(String tableName, 
			String filterQuery, String rankColumn, GlideRecord currentRecord) {
		RankUpdateHelper rankUpdateHelper = new RankUpdateHelper(tableName, filterQuery, rankColumn, currentRecord);
		rankUpdateHelper.process();
	}
	
	public void jsFunction_checkCycle(String taskId) {
		if(PlannedTaskValueValidator.isValidString(taskId) )
			this.detectCycle(taskId);
	}

	private void detectCycle(String taskId) {
		if(PlannedTaskValueValidator.isValidString(taskId)) {
			PlannedTaskRecordDecorator taskRecordDecorator = new PlannedTaskRecordDecorator();
			taskRecordDecorator.get(taskId);
			String topTaskSysId = taskRecordDecorator.getValue(TOP_TASK);
			if(PlannedTaskValueValidator.isValidString(topTaskSysId)) {
				PlannedTaskStructure structure = this.loadStructure(topTaskSysId);
				this.detectCycle(structure);
			} else {
				SysLog.error("PlannedTaskAPI", "Provided task has invalid top_task");
			}
		}
	}

	@Override
	public String getClassName() {
		return PlannedTaskAPI.class.getSimpleName();
	}
	
	public boolean jsFunction_isInterProjectRelationValid(Object glideRecord) {
		if( null != glideRecord) {
			GlideRecord gr = (GlideRecord) glideRecord;
			String parentId = gr.getValue(PlannedTaskFieldConstants.PARENT);
			String childId = gr.getValue(PlannedTaskFieldConstants.CHILD);
			if(PlannedTaskValueValidator.isValidString(parentId) && 
					PlannedTaskValueValidator.isValidString(childId)) {
				GlideRecord parentGr = PlannedTaskDBService.get(parentId);
				GlideRecord childGr = PlannedTaskDBService.get(childId);
				if(parentGr.isValidRecord() && childGr.isValidRecord()) {
					PlannedTask parent = (new PlannedTaskFactory()).create(parentGr);
					PlannedTask child = (new PlannedTaskFactory()).create(childGr);
					if( null != parent.getTopTask() && null != child.getTopTask() &&
							!parent.getTopTask().equals(child.getTopTask())){
						InterProjectCycleDetector cycleDectector = new InterProjectCycleDetector();
						try {
							cycleDectector.detectCyclesBetweenProjects(parent, child);
							return true;
						} catch (CyclicException e) {
							return false;
						}
					}
				}
			}
		}
		return false;
	}
	
	public String jsFunction_interProjectRelationCallback(Object parent, Object child, String origSysId, String interProjectDependencyType) {
		GlideRecord task1 = (GlideRecord) parent;
		GlideRecord task2 = (GlideRecord) child;
		try {
			InterProjectRelationCallback.afterCreate(relation -> relation.from(task1.getValue("sys_id")).to(task2.getValue("sys_id")).asShadowOf(origSysId).interProjectDependencyType(interProjectDependencyType));
		} catch (PlannedTaskException | PTScheduleException e) {
			SysLog.error("PlannedTaskAPI", "InterProjectRelationCallback error");
			return e.getMessage();
		}
		return "success";
	}
	
	public void jsFunction_recalculateWithDirtyTasks(String topTaskId, String dirtyTasks) {
		try {
			recalculateWithDirtyTasks(topTaskId, dirtyTasks);
		} catch (PlannedTaskException e) {
			Log.error("Unable to recalculate the inter related project due to exception -" + 
					e.getMessage());
		} catch (PTScheduleException e) {
			Log.error("Unable to recalculate the inter related project due to schedule exception -" + 
					e.getMessage());
		}
	}
	
	public void recalculateWithDirtyTasks(String topTaskId, String dirtyTasks) throws PlannedTaskException, PTScheduleException {
		Log.info("Into PlannedTaskAPI: recalculateWithDirtyTasks -> " + topTaskId + " | " + 
					dirtyTasks);
		PlannedTaskRecordDecorator taskRecordDecorator = new PlannedTaskRecordDecorator();
		taskRecordDecorator.get(topTaskId);
		String topTaskSysId = taskRecordDecorator.getValue(TOP_TASK);
		PlannedTaskStructure structure = this.loadStructure(topTaskSysId);
		CycleDetector cycleDetector = new CycleDetector();
		if(structure.hasTasks() && !cycleDetector.detectCyle(structure)) {
			if(PlannedTaskValueValidator.isValidString(dirtyTasks)) {
				String[] taskIds = dirtyTasks.split(",");
				for (String taskId : taskIds) {
					PlannedTask task = structure.getTask(taskId);
					if(null != task) 
						task.setDirty(true);
				}
			}
			taskEngine.recalculate(structure, TimeZoneHelper.getUserTimezone(), this.constraints);
			this.getDataStore(false).save(structure);
		}
	}
	
	public String jsFunction_acceptNotification(String entityId, String sysClassName, String notificationSysId) {
		LogHelper.logInfo(PlannedTaskAPI.class.getName(), "PPM Into applyChange "+entityId);
		try {
			PlannedTaskNotificationHandler handler = new PlannedTaskNotificationHandler(notificationSysId);
			handler.acceptNotification();
			Map<String, Object> actionMap = handler.generateNotificationPayload();
			actionMap.put(PlannedTaskFieldConstants.SECURITY_VALIDATION,  "false");
			return this.applyChanges(entityId, sysClassName, null, actionMap);
		} catch (PlannedTaskException e) {
			return this.stringify(this.getErrorGantt(null, e));
		}
	}
	
	public String jsFunction_rejectNotification(String notificationSysId) {
		PlannedTaskJSONData jsonData = new PlannedTaskJSONData();
		(new PlannedTaskNotificationHandler(notificationSysId)).rejectNotification();
		jsonData.setStatus(STATUS_SUCCESS);
		jsonData.setStatusMessage(STATUS_SUCCESS);
		return this.stringify(jsonData); // string-ified status should handle the return
	}

	public String jsFunction_applyTemplate(String projectId, String templateId, String startDate) {
		try {
			ProjectTemplate.apply(templateId, projectId, startDate);
			PlannedTaskJSONData jsonData = new PlannedTaskJSONData();
			jsonData.setFullReload(true); // A full reload is required as the tasks are applied from template(s)
			return this.stringify(jsonData);
		} catch (PlannedTaskException e) {
			return this.stringify(this.getErrorGantt(null, e));
		}
	}

	public void jsFunction_validateWbs(String projectId) {
		PlannedTaskStructureLoader structureLoader = new PlannedTaskStructureLoader();
		PlannedTaskStructure structure = structureLoader.loadStructure(projectId);
		taskEngine.validateWbs(structure);
		this.getDataStore(false).save(structure);
	}
	
	public String jsFunction_getReferenceValue(String table, String column,
			String sysId, String chars, String window, String maxToReturn) {
		ReferenceValues referenceValues = ReferenceHelper.getReferenceValue(table, column, 
				sysId, chars, window, maxToReturn);
		JSONObject json = new JSONObject();
		json.put("items", referenceValues.getReferenceValues());
		json.put("total", referenceValues.getRowCount());
		return this.stringify(json);
	}
	
	public String jsFunction_getLatestStartDate(String taskSysId) {
		LogHelper.logInfo(PlannedTaskAPI.class.getName(), "PPM Into getLatestStartDate "+taskSysId);
		PlannedTaskStructure structure = null;
		JSONObject jsonObject = new JSONObject();
		try {
			if (PlannedTaskValueValidator.isValidString(taskSysId)) {
				structure = loadStructure(taskSysId);
			    structure.getTasks().forEach(t -> t.setDirty(true));
			    structure.getRelations().forEach(r -> r.setProcessFlag(true));
			    this.taskEngine.recalculate(structure, TimeZoneHelper.getUserTimezone(), 
						this.constraints);
			    if(null != structure.getTask(taskSysId)) {
			    	PlannedTask task = structure.getTask(taskSysId);
			    	jsonObject.put("status", PlannedTaskFieldConstants.STATUS_SUCCESS);
					jsonObject.put("mesage", PlannedTaskFieldConstants.STATUS_SUCCESS);
					jsonObject.put(PlannedTaskFieldConstants.RELATION_APPLIED, task.getRelationApplied());
					jsonObject.put(PlannedTaskFieldConstants.START_DATE, task.getStartDate().getValue());
					return JSONUtil.stringify(jsonObject);
			    }
			} 
		} catch(PlannedTaskException | PTScheduleException e) {
			SysLog.error(PlannedTaskAPI.class.getName(), e.getMessage());
			jsonObject.put("status", PlannedTaskFieldConstants.STATUS_ERROR);
			jsonObject.put("mesage", e.getMessage());
			return JSONUtil.stringify(jsonObject);
		}
		jsonObject.put("status", PlannedTaskFieldConstants.STATUS_ERROR);
		jsonObject.put("mesage", "unable_to_calculate_latest_start_date");
		return JSONUtil.stringify(jsonObject);
	}
	
	public void jsFunction_updateProjectState(Object topTaskNode, Object taskKeyVals, String state) {
		LogHelper.logMsg(this.getClass().getName(), "PPM Into updateProjectState -> " + state);
		HashMap<String, String> hashTaskKeyVals = GlideRhinoHelper.getMapFromRhino(taskKeyVals);
		GlideRecord taskRecord = (GlideRecord) topTaskNode;
		if(taskRecord.getValue(SYS_ID).equals(taskRecord.getValue(TOP_TASK))) {
			ProjectStateHelper projectStateHelper = new ProjectStateHelper();
			projectStateHelper.changeState(taskRecord, hashTaskKeyVals, state);
		}
	}
	
	public void jsFunction_clearRecordCache(String taskSysId) {
		LogHelper.logInfo(PlannedTaskAPI.class.getName(), "PPM Into clearRecordCache "+taskSysId);
		RecordCacheCleaner.clearTaskCache(taskSysId);
	}
	
	public GlideRecord jsFunction_getAncestors(Object node, boolean inclusive) {
		if (node == null || !(node instanceof GlideRecord)) {
			Log.warn("getAncestors() was passed an invalid GlideRecord");
			return new GlideRecord(PlannedTaskTableConstants.PLANNED_TASK);
		}
		
		GlideRecord glideNode = (GlideRecord) node;
		String topTask = glideNode.getValue(TOP_TASK);
		if (StringUtil.nil(topTask)) {
			Log.warn("getAncestors() was passed a gliderecord with no toptask");
			return new GlideRecord(PlannedTaskTableConstants.PLANNED_TASK);
		}
		
		List<String> sysids = new ArrayList<String>();
		if (inclusive)
			sysids.add(glideNode.getValue(SYS_ID));
		
		GlideRecord parent = new GlideRecord(PlannedTaskTableConstants.PLANNED_TASK);
		String parentId = glideNode.getValue(PARENT);
		while (parent.get(parentId)) {
			sysids.add(parent.getValue(SYS_ID));
			parentId = parent.getValue(PARENT);
			parent = new GlideRecord(PlannedTaskTableConstants.PLANNED_TASK);
		}
		
		GlideRecord query = new GlideRecord(PlannedTaskTableConstants.PLANNED_TASK);
		query.addQuery(SYS_ID, sysids);
		query.orderBy(LEVEL, true);
		query.query();

		return query;
	}
	
	public String jsFunction_scheduleDates(String taskSysId) {
		LogHelper.logInfo(PlannedTaskAPI.class.getName(), "PPM Into scheduleDates "+taskSysId);
		if(PlannedTaskValueValidator.isValidString(taskSysId)) {
			ScheduleDecorator timeSpanDecorator = new ScheduleDecorator();
			return timeSpanDecorator.decorateTimeSpansForTaskId(taskSysId);
		}
		return "{}";
	}
	
	public String jsFunction_scheduleYears(String taskSysId, String startYear, String endYear) {
		LogHelper.logInfo(PlannedTaskAPI.class.getName(), "PPM Into scheduleDates "+ taskSysId
				+ " - " + startYear + " | " + endYear);
		if(PlannedTaskValueValidator.isValidString(taskSysId) && 
				PlannedTaskValueValidator.isValidString(startYear) &&
				PlannedTaskValueValidator.isValidString(endYear)) {
			ScheduleDecorator timeSpanDecorator = new ScheduleDecorator();
			return timeSpanDecorator.decorateTimeSpansForTaskForYears(taskSysId, startYear, endYear);
		} else if(PlannedTaskValueValidator.isValidString(taskSysId))
			return this.jsFunction_scheduleDates(taskSysId);
		return "{}";
	}
}
