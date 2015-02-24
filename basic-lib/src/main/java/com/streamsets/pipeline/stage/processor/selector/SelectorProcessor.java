/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.stage.processor.selector;

import com.streamsets.pipeline.api.BatchMaker;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.base.OnRecordErrorException;
import com.streamsets.pipeline.api.base.RecordProcessor;
import com.streamsets.pipeline.el.ELEvaluator;
import com.streamsets.pipeline.el.ELRecordSupport;
import com.streamsets.pipeline.el.ELStringSupport;
import com.streamsets.pipeline.el.ELUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.jsp.el.ELException;
import java.util.List;
import java.util.Map;

public class SelectorProcessor extends RecordProcessor {
  private static final Logger LOG = LoggerFactory.getLogger(SelectorProcessor.class);

  private final List<Map<String, String>> lanePredicates;
  private final Map<String, ?> constants;

  public SelectorProcessor(List<Map<String, String>> lanePredicates, Map<String, ?> constants) {
    this.lanePredicates = lanePredicates;
    this.constants = constants;
  }

  private String[][] predicateLanes;
  private ELEvaluator elEvaluator;
  private ELEvaluator.Variables variables;
  private String defaultLane;

  @Override
  protected List<ConfigIssue> validateConfigs()  throws StageException {
    List<ConfigIssue> issues = super.validateConfigs();
    if (lanePredicates == null || lanePredicates.size() == 0) {
      issues.add(getContext().createConfigIssue(Groups.CONDITIONS.name(), "lanePredicates", Errors.SELECTOR_00));
    } else {
      if (getContext().getOutputLanes().size() != lanePredicates.size()) {
        issues.add(getContext().createConfigIssue(Groups.CONDITIONS.name(), "lanePredicates", Errors.SELECTOR_01,
                                                  lanePredicates.size(),
                                                  getContext().getOutputLanes().size()));
      } else {
        predicateLanes = parsePredicateLanes(lanePredicates, issues);
        if (!predicateLanes[predicateLanes.length - 1][0].equals("default")) {
          issues.add(getContext().createConfigIssue(Groups.CONDITIONS.name(), "lanePredicates", Errors.SELECTOR_07));
        } else {
          variables = ELUtils.parseConstants(constants, getContext(), Groups.CONDITIONS.name(), "constants",
                                             Errors.SELECTOR_04, issues);
          elEvaluator = new ELEvaluator();
          ELRecordSupport.registerRecordFunctions(elEvaluator);
          ELStringSupport.registerStringFunctions(elEvaluator);
          ELRecordSupport.setRecordInContext(variables, getContext().createRecord("forValidation"));
          for (int i = 0; i < predicateLanes.length - 1; i++) {
            String[] predicateLane = predicateLanes[i];
            if (!predicateLane[0].startsWith("${") || !predicateLane[0].endsWith("}")) {
              issues.add(getContext().createConfigIssue(Groups.CONDITIONS.name(), "lanePredicates", Errors.SELECTOR_08,
                                                        predicateLane[0]));
            } else {
              ELUtils.validateExpression(elEvaluator, variables, predicateLane[0], getContext(),
                                         Groups.CONDITIONS.name(), "lanePredicates", Errors.SELECTOR_03,
                                         Boolean.class, issues);
            }
          }
        }
      }
    }
    return issues;
  }

  private String[][] parsePredicateLanes(List<Map<String, String>> predicateLanesList, List<ConfigIssue> issues)
      throws StageException {
    String[][] predicateLanes = new String[predicateLanesList.size()][];
    int count = 0;
    for (int i = 0; i < predicateLanesList.size(); i++) {
      Map<String, String> predicateLaneMap = predicateLanesList.get(i);
      String outputLane = predicateLaneMap.get("outputLane");
      Object predicate = predicateLaneMap.get("predicate");
      if (!getContext().getOutputLanes().contains(outputLane)) {
        issues.add(getContext().createConfigIssue(Groups.CONDITIONS.name(), "lanePredicates", Errors.SELECTOR_02,
                                                  outputLane, predicate));
      }
      predicateLanes[count] = new String[2];
      predicateLanes[count][0] = (String) predicate;
      predicateLanes[count][1] = outputLane;
      LOG.debug("Condition:'{}' to stream:'{}'", predicate, outputLane);
      count++;
    }
    return predicateLanes;
  }

  @Override
  protected void init() throws StageException {
    super.init();
    defaultLane = predicateLanes[predicateLanes.length - 1][1];
  }

  @Override
  protected void process(Record record, BatchMaker batchMaker) throws StageException {
    boolean matchedAtLeastOnePredicate = false;
    ELRecordSupport.setRecordInContext(variables, record);
    for (int i = 0; i < predicateLanes.length - 1; i ++) {
      String[] pl = predicateLanes[i];
      try {
        if (elEvaluator.eval(variables, pl[0], Boolean.class)) {
          LOG.trace("Record '{}' satisfies condition '{}', going to '{}' output stream",
                    record.getHeader().getSourceId(), pl[0], pl[1]);
          batchMaker.addRecord(record, pl[1]);
          matchedAtLeastOnePredicate = true;
        }
      } catch (ELException ex) {
        throw new OnRecordErrorException(Errors.SELECTOR_09, record.getHeader().getSourceId(), pl[0], ex.getMessage(),
                                         ex);
      }
    }
    if (!matchedAtLeastOnePredicate) {
      LOG.trace("Record '{}' does not satisfy any condition, going to default output stream",
                record.getHeader().getSourceId());
      batchMaker.addRecord(record, defaultLane);
    }
  }

}
