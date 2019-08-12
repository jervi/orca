/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.front50.tasks;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.spinnaker.orca.RetryableTask;
import com.netflix.spinnaker.orca.TaskResult;
import com.netflix.spinnaker.orca.front50.Front50Service;
import com.netflix.spinnaker.orca.front50.model.DeliveryConfig;
import com.netflix.spinnaker.orca.pipeline.model.Stage;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import retrofit.RetrofitError;

@Component
public class MonitorFront50Task implements RetryableTask {
  private final Logger log = LoggerFactory.getLogger(getClass());
  private final Front50Service front50Service;

  private final int successThreshold;
  private final int gracePeriodMs;

  private final ObjectMapper objectMapper;

  @Autowired
  public MonitorFront50Task(
      Optional<Front50Service> front50Service,
      ObjectMapper objectMapper,
      @Value("${tasks.monitor-front50-task.success-threshold:0}") int successThreshold,
      @Value("${tasks.monitor-front50-task.grace-period-ms:5000}") int gracePeriodMs) {
    this.front50Service = front50Service.orElse(null);
    this.objectMapper = objectMapper;
    this.successThreshold = successThreshold;

    // some storage providers round the last modified time to the nearest second, this allows for a
    // configurable
    // grace period when comparing against a stage start time (always at millisecond granularity).
    this.gracePeriodMs = gracePeriodMs;
  }

  @Override
  public long getBackoffPeriod() {
    return TimeUnit.SECONDS.toMillis(5);
  }

  @Override
  public long getTimeout() {
    return TimeUnit.SECONDS.toMillis(90);
  }

  @Nonnull
  @Override
  public TaskResult execute(@Nonnull Stage stage) {
    if (front50Service == null) {
      throw new UnsupportedOperationException(
          "Front50 was not enabled. Fix this by setting front50.enabled: true");
    }

    if (successThreshold == 0) {
      return TaskResult.SUCCEEDED;
    }

    StageData stageData = stage.mapTo(StageData.class);
    if (stageData.pipelineId != null) {
      try {
        return monitor(this::getPipeline, stageData.pipelineId, stage.getStartTime());
      } catch (Exception e) {
        log.error(
            "Unable to verify that pipeline has been updated (executionId: {}, pipeline: {})",
            stage.getExecution().getId(),
            stageData.pipelineName,
            e);
        return TaskResult.RUNNING;
      }
    } else if (stageData.deliveryConfig != null) {
      String deliveryConfigId = stageData.deliveryConfig.getId();
      try {
        return monitor(this::getDeliveryConfig, deliveryConfigId, stage.getStartTime());
      } catch (Exception e) {
        log.error(
            "Unable to verify that delivery config has been updated (executionId: {}, configId: {})",
            stage.getExecution().getId(),
            deliveryConfigId,
            e);
        return TaskResult.RUNNING;
      }
    } else {
      log.warn(
          "No id found, unable to verify that the object has been updated (executionId: {})",
          stage.getExecution().getId());
    }
    if (stageData.serviceAccount != null) {
      try {
        return monitor(this::getServiceAccount, stageData.serviceAccount, stage.getStartTime());
      } catch (Exception e) {
        log.error(
            "Unable to verify that automatic service account has been updated (executionId: {}, serviceAccount: {})",
            stage.getExecution().getId(),
            stageData.serviceAccount,
            e);
        return TaskResult.RUNNING;
      }
    }

    return TaskResult.SUCCEEDED;
  }

  private TaskResult monitor(
      Function<String, Optional<Map<String, Object>>> getObjectFunction,
      String id,
      Long startTime) {
    /*
     * Some storage services (notably S3) are eventually consistent when versioning is enabled.
     *
     * This "dirty hack" attempts to ensure that each underlying instance of Front50 has cached an _updated copy_
     * of the modified resource.
     *
     * It does so by making multiple requests (currently only applies to pipelines) with the expectation that they
     * will round-robin across all instances of Front50.
     */
    for (int i = 0; i < successThreshold; i++) {
      Optional<Map<String, Object>> object = getObjectFunction.apply(id);
      if (!object.isPresent()) {
        return TaskResult.RUNNING;
      }

      Long lastModifiedTime;
      if (object.get().containsKey("updateTs")) {
        lastModifiedTime = Long.valueOf(object.get().get("updateTs").toString());
      } else {
        lastModifiedTime = Long.valueOf(object.get().get("lastModified").toString());
      }

      if (lastModifiedTime < (startTime - gracePeriodMs)) {
        return TaskResult.RUNNING;
      }

      try {
        // small delay between verification attempts
        Thread.sleep(1000);
      } catch (InterruptedException ignored) {
      }
    }

    return TaskResult.SUCCEEDED;
  }

  private Optional<Map<String, Object>> getPipeline(String id) {
    List<Map<String, Object>> pipelines = front50Service.getPipelineHistory(id, 1);
    return pipelines.isEmpty() ? Optional.empty() : Optional.of(pipelines.get(0));
  }

  @SuppressWarnings("unchecked")
  private Optional<Map<String, Object>> getDeliveryConfig(String id) {
    try {
      DeliveryConfig deliveryConfig = front50Service.getDeliveryConfig(id);
      return Optional.of(objectMapper.convertValue(deliveryConfig, Map.class));
    } catch (RetrofitError e) {
      // ignore an unknown (404) or unauthorized (403, 401)
      if (e.getResponse() != null
          && Arrays.asList(404, 403, 401).contains(e.getResponse().getStatus())) {
        return Optional.empty();
      } else {
        throw e;
      }
    }
  }

  private Optional<Map<String, Object>> getServiceAccount(String serviceAccountId) {
    try {
      front50Service.invalidateServiceAccountCache(serviceAccountId);
      return Optional.ofNullable(front50Service.getServiceAccount(serviceAccountId));
    } catch (RetrofitError e) {
      if (e.getResponse() != null
          && Arrays.asList(404, 403, 401).contains(e.getResponse().getStatus())) {
        return Optional.empty();
      } else {
        throw e;
      }
    }
  }

  private static class StageData {
    public String application;

    @JsonProperty("pipeline.id")
    public String pipelineId;

    @JsonProperty("pipeline.name")
    public String pipelineName;

    @JsonProperty("pipeline.serviceAccount")
    public String serviceAccount;

    public DeliveryConfig deliveryConfig;
  }
}
