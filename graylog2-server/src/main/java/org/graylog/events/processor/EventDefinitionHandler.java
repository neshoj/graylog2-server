/**
 * This file is part of Graylog.
 *
 * Graylog is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.graylog.events.processor;

import org.graylog.scheduler.DBJobDefinitionService;
import org.graylog.scheduler.DBJobTriggerService;
import org.graylog.scheduler.JobDefinitionDto;
import org.graylog.scheduler.JobTriggerDto;
import org.graylog.scheduler.clock.JobSchedulerClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Handles event definitions and creates scheduler job definitions and job triggers.
 *
 * <b>Caveat:</b> These handlers modify different database documents without using any transactions. That means we can
 * run into concurrency issues and partial operations.
 *
 * TODO: Make use of transactions once our database library supports it
 */
public class EventDefinitionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(EventDefinitionHandler.class);

    private final DBEventDefinitionService eventDefinitionService;
    private final DBJobDefinitionService jobDefinitionService;
    private final DBJobTriggerService jobTriggerService;
    private final JobSchedulerClock clock;

    @Inject
    public EventDefinitionHandler(DBEventDefinitionService eventDefinitionService,
                                  DBJobDefinitionService jobDefinitionService,
                                  DBJobTriggerService jobTriggerService,
                                  JobSchedulerClock clock) {
        this.eventDefinitionService = eventDefinitionService;
        this.jobDefinitionService = jobDefinitionService;
        this.jobTriggerService = jobTriggerService;
        this.clock = clock;
    }

    /**
     * Creates a new event definition and a corresponding scheduler job definition and trigger.
     *
     * @param unsavedEventDefinition the event definition to save
     * @return the created event definition
     */
    public EventDefinitionDto create(EventDefinitionDto unsavedEventDefinition) {
        final EventDefinitionDto eventDefinition = createEventDefinition(unsavedEventDefinition);

        try {
            createJobDefinitionAndTriggerIfScheduled(eventDefinition);
        } catch (Exception e) {
            // Cleanup if anything goes wrong
            LOG.error("Removing event definition <{}/{}> because of an error creating the job definition",
                    eventDefinition.id(), eventDefinition.title(), e);
            eventDefinitionService.delete(eventDefinition.id());
            throw e;
        }

        return eventDefinition;
    }

    /**
     * Updates an existing event definition and its corresponding scheduler job definition and trigger.
     *
     * @param updatedEventDefinition the event definition to update
     * @return the updated event definition
     */
    public EventDefinitionDto update(EventDefinitionDto updatedEventDefinition) {
        // Grab the old record so we can revert to it if something goes wrong
        final Optional<EventDefinitionDto> oldEventDefinition = eventDefinitionService.get(updatedEventDefinition.id());

        final EventDefinitionDto eventDefinition = updateEventDefinition(updatedEventDefinition);

        try {
            updateJobDefinitionAndTriggerIfScheduled(eventDefinition);
        } catch (Exception e) {
            // Cleanup if anything goes wrong
            LOG.error("Reverting to old event definition <{}/{}> because of an error updating the job definition",
                    eventDefinition.id(), eventDefinition.title(), e);
            oldEventDefinition.ifPresent(eventDefinitionService::save);
            throw e;
        }

        return eventDefinition;
    }

    /**
     * Deletes an existing event definition and its corresponding scheduler job definition and trigger.
     *
     * @param eventDefinitionId the event definition to delete
     * @return true if the event definition got deleted, false otherwise
     */
    public boolean delete(String eventDefinitionId) {
        final Optional<EventDefinitionDto> optionalEventDefinition = eventDefinitionService.get(eventDefinitionId);
        if (!optionalEventDefinition.isPresent()) {
            return false;
        }

        final EventDefinitionDto eventDefinition = optionalEventDefinition.get();

        getJobDefinition(eventDefinition)
                .ifPresent(jobDefinition -> deleteJobDefinitionAndTrigger(jobDefinition, eventDefinition));

        LOG.debug("Deleting event definition <{}/{}>", eventDefinition.id(), eventDefinition.title());
        return eventDefinitionService.delete(eventDefinitionId) > 0;
    }

    private EventDefinitionDto createEventDefinition(EventDefinitionDto unsavedEventDefinition) {
        final EventDefinitionDto eventDefinition = eventDefinitionService.save(unsavedEventDefinition);
        LOG.debug("Created event definition <{}/{}>", eventDefinition.id(), eventDefinition.title());
        return eventDefinition;
    }

    private EventDefinitionDto updateEventDefinition(EventDefinitionDto updatedEventDefinition) {
        final EventDefinitionDto eventDefinition = eventDefinitionService.save(updatedEventDefinition);
        LOG.debug("Updated event definition <{}/{}>", eventDefinition.id(), eventDefinition.title());
        return eventDefinition;
    }

    private JobDefinitionDto newJobDefinition(EventDefinitionDto eventDefinition, EventProcessorSchedulerConfig schedulerConfig) {
        return JobDefinitionDto.builder()
                .title(eventDefinition.title())
                .description(eventDefinition.description())
                .config(schedulerConfig.jobDefinitionConfig())
                .build();
    }

    private JobDefinitionDto createJobDefinition(EventDefinitionDto eventDefinition, EventProcessorSchedulerConfig schedulerConfig) {
        final JobDefinitionDto jobDefinition = jobDefinitionService.save(newJobDefinition(eventDefinition, schedulerConfig));
        LOG.debug("Created scheduler job definition <{}/{}> for event definition <{}/{}>", jobDefinition.id(),
                jobDefinition.title(), eventDefinition.id(), eventDefinition.title());
        return jobDefinition;
    }

    private void createJobDefinitionAndTrigger(EventDefinitionDto eventDefinition,
                                               EventProcessorSchedulerConfig schedulerConfig) {
        final JobDefinitionDto jobDefinition = createJobDefinition(eventDefinition, schedulerConfig);

        try {
            createJobTrigger(eventDefinition, jobDefinition, schedulerConfig);
        } catch (Exception e) {
            // Cleanup if anything goes wrong
            LOG.error("Removing job definition <{}/{}> because of an error creating the job trigger",
                    jobDefinition.id(), jobDefinition.title(), e);
            jobDefinitionService.delete(jobDefinition.id());
            throw e;
        }
    }

    private void createJobDefinitionAndTriggerIfScheduled(EventDefinitionDto eventDefinition) {
        getJobSchedulerConfig(eventDefinition)
                .ifPresent(schedulerConfig -> createJobDefinitionAndTrigger(eventDefinition, schedulerConfig));
    }

    private Optional<JobDefinitionDto> getJobDefinition(EventDefinitionDto eventDefinition) {
        return jobDefinitionService.getByConfigField(EventProcessorExecutionJob.Config.FIELD_EVENT_DEFINITION_ID, eventDefinition.id());
    }

    private JobDefinitionDto getJobDefinitionOrThrowISE(EventDefinitionDto eventDefinition) {
        return getJobDefinition(eventDefinition)
                .orElseThrow(() -> new IllegalStateException("Couldn't find job definition for event definition <" + eventDefinition.id() + ">"));
    }

    private JobDefinitionDto updateJobDefinition(EventDefinitionDto eventDefinition,
                                                 JobDefinitionDto oldJobDefinition,
                                                 EventProcessorSchedulerConfig schedulerConfig) {
        // Update the existing object to make sure we keep the ID
        final JobDefinitionDto unsavedJobDefinition = oldJobDefinition.toBuilder()
                .title(eventDefinition.title())
                .description(eventDefinition.description())
                .config(schedulerConfig.jobDefinitionConfig())
                .build();

        final JobDefinitionDto jobDefinition = jobDefinitionService.save(unsavedJobDefinition);

        LOG.debug("Updated scheduler job definition <{}/{}> for event definition <{}/{}>", jobDefinition.id(),
                jobDefinition.title(), eventDefinition.id(), eventDefinition.title());

        return jobDefinition;
    }

    private void updateJobDefinitionAndTrigger(EventDefinitionDto eventDefinition,
                                               EventProcessorSchedulerConfig schedulerConfig) {
        // Grab the old record so we can revert to it if something goes wrong
        final JobDefinitionDto oldJobDefinition = getJobDefinitionOrThrowISE(eventDefinition);

        final JobDefinitionDto jobDefinition = updateJobDefinition(eventDefinition, oldJobDefinition, schedulerConfig);

        try {
            updateJobTrigger(eventDefinition, jobDefinition, schedulerConfig);
        } catch (Exception e) {
            // Cleanup if anything goes wrong
            LOG.error("Reverting to old job definition <{}/{}> because of an error updating the job trigger",
                    jobDefinition.id(), jobDefinition.title(), e);
            jobDefinitionService.save(oldJobDefinition);
            throw e;
        }
    }

    private void updateJobDefinitionAndTriggerIfScheduled(EventDefinitionDto eventDefinition) {
        getJobSchedulerConfig(eventDefinition)
                .ifPresent(schedulerConfig -> updateJobDefinitionAndTrigger(eventDefinition, schedulerConfig));
    }

    private void deleteJobDefinition(JobDefinitionDto jobDefinition, EventDefinitionDto eventDefinition) {
        LOG.debug("Deleting job definition <{}/{}> for event definition <{}/{}>", jobDefinition.id(),
                jobDefinition.title(), eventDefinition.id(), eventDefinition.title());
        jobDefinitionService.delete(jobDefinition.id());
    }

    private void deleteJobDefinitionAndTrigger(JobDefinitionDto jobDefinition, EventDefinitionDto eventDefinition) {
        deleteJobTrigger(jobDefinition, eventDefinition);
        deleteJobDefinition(jobDefinition, eventDefinition);
    }

    private JobTriggerDto newJobTrigger(JobDefinitionDto jobDefinition, EventProcessorSchedulerConfig schedulerConfig) {
        return JobTriggerDto.builderWithClock(clock)
                .jobDefinitionId(requireNonNull(jobDefinition.id(), "Job definition ID cannot be null"))
                .nextTime(clock.nowUTC())
                .schedule(schedulerConfig.schedule())
                .build();
    }

    private void createJobTrigger(EventDefinitionDto dto, JobDefinitionDto jobDefinition, EventProcessorSchedulerConfig schedulerConfig) {
        final JobTriggerDto jobTrigger = jobTriggerService.create(newJobTrigger(jobDefinition, schedulerConfig));
        LOG.debug("Created job trigger <{}> for job definition <{}/{}> and event definition <{}/{}>", jobTrigger.id(),
                jobDefinition.id(), jobDefinition.title(), dto.id(), dto.title());
    }

    private Optional<JobTriggerDto> getJobTrigger(JobDefinitionDto jobDefinition) {
        final List<JobTriggerDto> jobTriggers = jobTriggerService.getForJob(jobDefinition.id());

        if (jobTriggers.isEmpty()) {
            return Optional.empty();
        }

        // DBJobTriggerService#getForJob currently returns only one trigger. (raises an exception otherwise)
        // Once we allow multiple triggers per job definition, this code will fail. We need some kind of label
        // to figure out which trigger was created automatically. (e.g. event processor)
        // TODO: Fix this code for multiple triggers per job definition
        return Optional.ofNullable(jobTriggers.get(0));
    }

    private void updateJobTrigger(EventDefinitionDto eventDefinition,
                                  JobDefinitionDto jobDefinition,
                                  EventProcessorSchedulerConfig schedulerConfig) {
        final Optional<JobTriggerDto> optionalOldJobTrigger = getJobTrigger(jobDefinition);
        if (!optionalOldJobTrigger.isPresent()) {
            // Nothing to do if there are no job triggers to update
            return;
        }

        final JobTriggerDto oldJobTrigger = optionalOldJobTrigger.get();

        // Update the existing object to make sure we keep the ID
        final JobTriggerDto.Builder unsavedJobTriggerBuilder = oldJobTrigger.toBuilder()
                .jobDefinitionId(requireNonNull(jobDefinition.id(), "Job definition ID cannot be null"))
                .schedule(schedulerConfig.schedule())
                .nextTime(clock.nowUTC());

        // Calculate new scheduling times
        if (oldJobTrigger.data().isPresent()) {
            final EventProcessorExecutionJob.Config config = (EventProcessorExecutionJob.Config) jobDefinition.config();
            final EventProcessorExecutionJob.Data oldData = (EventProcessorExecutionJob.Data) oldJobTrigger.data().get();
            EventProcessorExecutionJob.Data newData = EventProcessorExecutionJob.Data.builder()
                    .timerangeFrom(oldData.timerangeFrom())
                    .timerangeTo(oldData.timerangeFrom().plus(config.processingWindowSize()))
                    .build();
            unsavedJobTriggerBuilder.data(newData);
            unsavedJobTriggerBuilder.nextTime(newData.timerangeTo());
        }

        final JobTriggerDto jobTrigger = unsavedJobTriggerBuilder.build();
        jobTriggerService.update(jobTrigger);
        LOG.debug("Updated scheduler job trigger <{}> for job definition <{}/{}> and event definition <{}/{}>",
                jobTrigger.id(), jobDefinition.id(), jobDefinition.title(), eventDefinition.id(), eventDefinition.title());
    }

    private void deleteJobTrigger(JobDefinitionDto jobDefinition, EventDefinitionDto eventDefinition) {
        final Optional<JobTriggerDto> optionalJobTrigger = getJobTrigger(jobDefinition);
        if (!optionalJobTrigger.isPresent()) {
            return;
        }

        final JobTriggerDto jobTrigger = optionalJobTrigger.get();
        LOG.debug("Deleting scheduler job trigger <{}> for job definition <{}/{}> and event definition <{}/{}>",
                jobTrigger.id(), jobDefinition.id(), jobDefinition.title(), eventDefinition.id(), eventDefinition.title());
        jobTriggerService.delete(jobTrigger.id());
    }

    private Optional<EventProcessorSchedulerConfig> getJobSchedulerConfig(EventDefinitionDto eventDefinition) {
        return eventDefinition.config().toJobSchedulerConfig(eventDefinition, clock);
    }
}
