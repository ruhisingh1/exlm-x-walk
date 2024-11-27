package com.exlm.core.listeners;

import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.jcr.api.SlingRepository;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.EventConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.exlm.core.service.TranslationWorkflowConfig;
import com.exlm.core.workflows.TranslationWorkflowTrigger;

import java.util.Arrays;
import java.util.List;

/**
 * Listens for replication events and triggers translation workflows on click on
 * English pages.
 */
@Component(service = EventHandler.class, immediate = true, property = {
		EventConstants.EVENT_TOPIC + "=com/day/cq/replication",
		EventConstants.EVENT_FILTER + "=(paths=/content/exlm/global/en*)" })
public class ReplicationEventListener implements EventHandler {

	private static final Logger LOGGER = LoggerFactory.getLogger(ReplicationEventListener.class);

	private static final String PATHS = "paths";

	private static final String SOURCE_LANGUAGE = "en";

	@Reference
	private ResourceResolverFactory resolverFactory;

	@Reference
	private TranslationWorkflowTrigger workflowTrigger;

	@Reference
	public SlingRepository slingRepository;

	@Reference
	private TranslationWorkflowConfig translationWorkflowConfig;

	/**
	 * Handles the replication event and triggers translation workflows on click on
	 * English pages.
	 *
	 * @param event The replication event.
	 */
	@Override
	public void handleEvent(Event event) {
		String[] paths = (String[]) event.getProperty(PATHS);
		String environmentType = translationWorkflowConfig.getEnvironmentType();
		String[] destinationLanguages = translationWorkflowConfig.getDestinationLanguages();
		LOGGER.info("Environment is: {}", environmentType);
		if (!environmentType.contains("non-prod") && paths != null && paths.length > 0) {
			List<String> englishPaths = Arrays.asList(paths);
			LOGGER.info("Replication event detected for English pages: {}", englishPaths);
			initiateWorkflow(englishPaths, destinationLanguages);
		} else {
			LOGGER.info("No paths found in the replication event");
		}
	}

	/**
	 * Initiates translation workflows for the specified paths.
	 *
	 * @param paths                The paths to initiate workflows for.
	 * @param destinationLanguages The destination languages for translation.
	 */
	private void initiateWorkflow(List<String> paths, String[] destinationLanguages) {
		try {
			workflowTrigger.triggerTranslationWorkflow(paths, SOURCE_LANGUAGE, destinationLanguages);
		} catch (Exception e) {
			LOGGER.error("Error initiating workflow for paths: {}", paths, e);
		}
	}
}