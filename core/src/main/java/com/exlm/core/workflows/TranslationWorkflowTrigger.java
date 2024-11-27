package com.exlm.core.workflows;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.jcr.api.SlingRepository;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import com.day.cq.workflow.WorkflowException;
import com.day.cq.workflow.WorkflowService;
import com.day.cq.workflow.WorkflowSession;
import com.day.cq.workflow.exec.WorkflowData;
import com.day.cq.workflow.model.WorkflowModel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.jcr.Session;

import java.util.*;

/**
 * Triggers translation workflows for content paths.
 */
@Component(service = TranslationWorkflowTrigger.class, immediate = true)
public class TranslationWorkflowTrigger {

	private static final Logger LOG = LoggerFactory.getLogger(TranslationWorkflowTrigger.class);

	@Reference
	private WorkflowService workflowService;

	@Reference
	public SlingRepository slingRepository;

	@Reference
	private ResourceResolverFactory resourceResolverFactory;

	public static final String EXL_SERVICE_USER = "exl-workflow-user";

	public static final Map<String, Object> AUTH_INFO = Collections
			.<String, Object>singletonMap(ResourceResolverFactory.SUBSERVICE, EXL_SERVICE_USER);

	private static final String CREATE_LANGUAGE_COPY = "/var/workflow/models/wcm-translation/create_language_copy";
	private static final String UPDATE_LANGUAGE_COPY = "/var/workflow/models/wcm-translation/update_language_copy";
	private static final String PREPARE_TRANSLATION_PROJECT = "/var/workflow/models/wcm-translation/prepare_translation_project";
	private static final String ADD_NEW_MULTI_LANG = "add_new_multi_lang";
	private static final String ADD_EXISTING = "add_existing";
	private static final String PROJECT_PATH = "/content/projects/exlm_auto_translation";
	private static final String PROJECT_TITLE = "EXLM Translation Project";
	private static final String WORKFLOW_LAUNCH_TITLE = "Translation review";
	private static final String CLOUD_CONFIG_PATH = "/conf/exlm";

	private static Map<String, Map<String, String>> existingTranslationProjects = new HashMap<>();

	/**
	 * Triggers translation workflow for the specified content paths.
	 *
	 * @param contentPaths   The paths of the content to be translated.
	 * @param sourceLanguage The source language of the content.
	 */
	public void triggerTranslationWorkflow(List<String> contentPaths, String sourceLanguage,
			String[] destinationLanguages) {

		WorkflowSession workflowSession = null;
		ResourceResolver resolver = null;
		try {
			resolver = resourceResolverFactory.getServiceResourceResolver(AUTH_INFO);
			workflowSession = workflowService.getWorkflowSession(resolver.adaptTo(Session.class));

			List<String> createLanguages = new ArrayList<>();
			List<String> updateLanguages = new ArrayList<>();

			for (String destinationLanguage : destinationLanguages) {
				boolean languageCopyExists = checkIfLanguageCopyExists(resolver, contentPaths, destinationLanguage);

				if (!languageCopyExists) {
					createLanguages.add(destinationLanguage);
				} else {
					updateLanguages.add(destinationLanguage);
				}
			}

			if (!createLanguages.isEmpty()) {
				triggerWorkflow(workflowSession, contentPaths, sourceLanguage, createLanguages, CREATE_LANGUAGE_COPY,
						false);
			}

			if (!updateLanguages.isEmpty()) {
				triggerWorkflow(workflowSession, contentPaths, sourceLanguage, updateLanguages, UPDATE_LANGUAGE_COPY,
						true);
			}

		} catch (WorkflowException e) {
			LOG.error("Error triggering translation workflow", e);
		} catch (Exception e) {
			LOG.error("Unexpected error occurred", e);
		} finally {
			if (workflowSession != null) {
				workflowSession.getSession().logout();
			}
			if (resolver != null) {
				resolver.close();
			}
		}

	}

	/**
	 * Triggers the translation workflow for the specified content paths and
	 * languages.
	 *
	 * @param workflowSession      The WorkflowSession object.
	 * @param contentPaths         The paths of the content to be translated.
	 * @param sourceLanguage       The source language of the content.
	 * @param destinationLanguages The languages to translate the content into.
	 * @param workflowModelPath    The path of the workflow model.
	 * @param isUpdate             Flag indicating whether it's an update workflow.
	 * @throws WorkflowException If an error occurs during workflow processing.
	 */
	private void triggerWorkflow(WorkflowSession workflowSession, List<String> contentPaths, String sourceLanguage,
			List<String> destinationLanguages, String workflowModelPath, boolean isUpdate) throws WorkflowException {
		WorkflowModel workflowModel = workflowSession.getModel(workflowModelPath);
		String jcrPath = isUpdate ? getLanguageCopy(contentPaths.get(0), destinationLanguages).split(";")[0]
				: StringUtils.join(contentPaths, ",");
		WorkflowData workflowData = workflowSession.newWorkflowData("JCR_PATH", jcrPath);
		setWorkflowMetadata(workflowData, sourceLanguage, destinationLanguages, contentPaths, isUpdate);
		workflowSession.startWorkflow(workflowModel, workflowData);

		LOG.info("Translation workflow triggered successfully for content paths: {}", contentPaths);
	}

	/**
	 * Checks if language copies exist for the specified content paths and
	 * destination language.
	 *
	 * @param resolver            The ResourceResolver to use for resource
	 *                            resolution.
	 * @param contentPaths        The paths of the content to check for language
	 *                            copies.
	 * @param destinationLanguage The destination language to check for a language
	 *                            copy.
	 * @return {@code true} if a language copy exists for the destination language,
	 *         {@code false} otherwise.
	 */
	private boolean checkIfLanguageCopyExists(ResourceResolver resolver, List<String> contentPaths,
			String destinationLanguage) {
		boolean languageCopyExists = false;
		try {
			for (String contentPath : contentPaths) {
				String[] pathSegments = contentPath.split("/");
				pathSegments[4] = destinationLanguage;
				String languageCopyPath = String.join("/", pathSegments);

				if (resolver.getResource(languageCopyPath) != null) {
					languageCopyExists = true;
					break;
				}
			}
		} catch (Exception e) {
			LOG.error("Error checking if language copy exists", e);
		}
		return languageCopyExists;
	}

	/**
	 * Constructs language copy paths for the given content path and destination
	 * languages.
	 *
	 * @param contentPath          The original content path.
	 * @param destinationLanguages The list of destination languages.
	 * @return A semicolon-separated string containing the language copy paths.
	 */
	private String getLanguageCopy(String contentPath, List<String> destinationLanguages) {
		List<String> languageCopyPaths = new ArrayList<>();
		try {
			String[] pathSegments = contentPath.split("/");
			for (String destinationLanguage : destinationLanguages) {
				pathSegments[4] = destinationLanguage;
				languageCopyPaths.add(String.join("/", pathSegments));
			}
		} catch (Exception e) {
			LOG.error("Error forming language copy", e);
		}
		return String.join(";", languageCopyPaths);
	}

	/**
	 * Checks if a project with the given project path already exists in the
	 * existingTranslationProjects map.
	 *
	 * @param projectPath The path of the project to check for existence.
	 * @return {@code true} if a project with the given project path exists,
	 *         {@code false} otherwise.
	 */
	private static boolean projectExists(String projectPath) {
		for (Map.Entry<String, Map<String, String>> entry : existingTranslationProjects.entrySet()) {
			Map<String, String> metaData = entry.getValue();
			String existingPath = metaData.get("projectFolderPath");
			if (existingPath.equals(projectPath)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Sets metadata for the workflow data.
	 *
	 * @param workflowData         The WorkflowData object to set metadata.
	 * @param sourceLanguage       The source language.
	 * @param destinationLanguages The list of destination languages.
	 * @param contentPaths         The paths of the content to be translated.
	 * @param isUpdate             Flag indicating whether it's an update workflow.
	 */
	private void setWorkflowMetadata(WorkflowData workflowData, String sourceLanguage,
			List<String> destinationLanguages, List<String> contentPaths, boolean isUpdate) {
		Map<String, Object> metaDataMap = new HashMap<>();
		String projectPath = PROJECT_PATH + "/" + PROJECT_TITLE.toLowerCase().replaceAll(" ", "_");
		if (projectExists(projectPath)) {
			metaDataMap.put("projectType", ADD_EXISTING);
			metaDataMap.put("projectFolderPath", projectPath);
		} else {
			metaDataMap.put("projectTitle", PROJECT_TITLE);
			metaDataMap.put("projectType", ADD_NEW_MULTI_LANG);
			metaDataMap.put("projectFolderPath", PROJECT_PATH);

			Map<String, String> projectMetadata = new HashMap<>();
			projectMetadata.put("projectTitle", PROJECT_TITLE);
			projectMetadata.put("projectFolderPath", projectPath);
			existingTranslationProjects.put("Project", projectMetadata);
		}
		metaDataMap.put("cloudConfigPath", CLOUD_CONFIG_PATH);
		String languageCopyPayload = getLanguageCopy(contentPaths.get(0), destinationLanguages);
		if (isUpdate) {
			metaDataMap.put("sourcePathList", languageCopyPayload);
			metaDataMap.put("destinationLanguage", destinationLanguages.get(0));
			metaDataMap.put("userId", EXL_SERVICE_USER);
			metaDataMap.put("workflowLaunchTitle", WORKFLOW_LAUNCH_TITLE);
		} else {
			metaDataMap.put("destinationLanguage", StringUtils.join(destinationLanguages, ","));
			metaDataMap.put("createLanguageRoot", false);
			metaDataMap.put("createNonEmptyAncestors", true);
			metaDataMap.put("initiatorUserId", EXL_SERVICE_USER);
		}

		metaDataMap.put("language", sourceLanguage);
		metaDataMap.put("languageList", StringUtils.join(destinationLanguages, ","));
		metaDataMap.put("deep", true);
		metaDataMap.put("translationWorkflowModel", PREPARE_TRANSLATION_PROJECT);
		metaDataMap.put("translationAutomaticApproveEnable", true);
		metaDataMap.put("translationAutomaticPromoteLaunchEnable", true);
		metaDataMap.put("translationAutomaticDeleteLaunchEnable", true);

		workflowData.getMetaDataMap().putAll(metaDataMap);
	}
}
