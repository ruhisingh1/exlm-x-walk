package com.exlm.core.schedulers;

import com.day.cq.replication.ReplicationActionType;
import com.day.cq.replication.ReplicationException;
import com.day.cq.replication.Replicator;
import com.exlm.core.utils.EXLUtils;
import com.day.cq.tagging.InvalidTagFormatException;
import com.day.cq.tagging.Tag;
import com.day.cq.tagging.TagManager;
import com.day.cq.wcm.api.PageManager;
import com.day.cq.wcm.api.Page;
import com.google.gson.*;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.sling.api.resource.*;
import org.apache.sling.commons.json.JSONException;
import org.apache.sling.commons.scheduler.ScheduleOptions;
import org.apache.sling.commons.scheduler.Scheduler;
import org.apache.sling.settings.SlingSettingsService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Session;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * This scheduler will synch configured ExL SCCM meta tags to AEM tags
 */
@Component(immediate = true, service = TagSynchScheduler.class)
@Designate(ocd = TagSynchSchedulerConfig.class)
public class TagSynchScheduler implements Runnable {

    private final Logger LOGGER = LoggerFactory.getLogger(this.getClass());

    @Reference
    private Scheduler scheduler;

    @Reference
    private ResourceResolverFactory resourceResolverFactory;

    @Reference
    private SlingSettingsService slingSettings;

    @Reference
    private Replicator replicator;

    private int schedulerID;

    private String[] exlAPIs;

    private String[] tagLocales;

    private static final int CONNECT_TIMEOUT = 10000;
    private static final int SOCKET_TIMEOUT = 30000;

    private static String tagNamespace = "exl:";

    private static final String FW_SLASH = "/";
    private static final String solutionTagName = "Solution";
    private static final String nestedElementKey = "Nested";
    private static final String versionsElementKey = "Versions";
    private static final String nameElementKey = "Name";
    private static final String tagLocalizationKeyPrefix = "jcr:title.";
    private static final String featureTagName = "feature";
    private static final String exlApiEnglishKey = "Name_en";
    private static String exlTagPath = "/content/cq:tags/exl";
    private static final String TAXONOMY_FOLDER_PATH = "/content/exlm/taxonomy";

    private CloseableHttpClient httpClient;

    public static final String EXL_SERVICE_USER = "exl-service-user";

    public static final Map<String, Object> AUTH_INFO =
            Collections.<String, Object>singletonMap(ResourceResolverFactory.SUBSERVICE, EXL_SERVICE_USER);

    @Activate
    protected void activate(TagSynchSchedulerConfig config) {
        if (isAuthor()) {
            removeScheduler();
            schedulerID = config.schedulerName().hashCode();
            httpClient = EXLUtils.getHttpClient();
            exlAPIs = config.exlTagApis();
            tagLocales = config.tagLocales();
            addScheduler(config);
            LOGGER.info("Activating Scheduler Job '{}'", schedulerID);
        }
    }

    @Modified
    protected void modified(TagSynchSchedulerConfig config) {
        removeScheduler();
        schedulerID = config.schedulerName().hashCode(); // update schedulerID
        exlAPIs = config.exlTagApis();
        tagLocales = config.tagLocales();
        addScheduler(config);
    }

    @Deactivate
    protected void deactivate(TagSynchSchedulerConfig config) {
        removeScheduler();
    }

    /**
     * Remove a scheduler based on the scheduler ID
     */
    private void removeScheduler() {
        LOGGER.info("Removing Scheduler Job '{}'", schedulerID);
        scheduler.unschedule(String.valueOf(schedulerID));
    }

    /**
     * Add a scheduler based on the scheduler ID
     */
    private void addScheduler(TagSynchSchedulerConfig config) {
        if (config.serviceEnabled()) {
            ScheduleOptions scheduleOptions = scheduler.EXPR(config.schedulerExpression());
            scheduleOptions.name(String.valueOf(schedulerID));
            scheduleOptions.canRunConcurrently(config.schedulerConcurrent());
            scheduleOptions.onLeaderOnly(true);
            scheduler.schedule(this, scheduleOptions);
            LOGGER.info("TagSynch Scheduler added successfully");
        } else {
            LOGGER.error("TagSynch Scheduler is Disabled, no scheduler job created");
        }
    }

    public boolean isAuthor() {
        return this.slingSettings.getRunModes().contains("author");
    }

    /**
     * Derive tag ID using tagName using Base64 encoding
     *
     * @param tagName
     * @return derivedTagID
     */
    private String deriveTagID(String tagName) {
        return Base64.getEncoder().encodeToString(tagName.getBytes());
    }

    /**
     * Persist/update the Tag node (always in EN) properties that hold the localized title
     * e.g. Node "/content/cq:tags/exl/experience-level/Experienced", props: [jcr:title:fr, jcr:title:it, etc]
     *
     * @param tagManager
     * @param englishTagID
     * @param exlTag
     * @param locale
     */
    private void persistTagLocalization(TagManager tagManager, String englishTagID, JsonElement exlTag, String locale) {
        Tag tag = tagManager.resolve(englishTagID);
        if (tag != null) {
            Resource tagResource = tag.adaptTo(Resource.class);
            ModifiableValueMap mvm = tagResource.adaptTo(ModifiableValueMap.class);
            String keyName = tagLocalizationKeyPrefix + locale;
            String localeTagName = ((JsonObject) exlTag).getAsJsonPrimitive(nameElementKey).getAsString();
            mvm.put(keyName, localeTagName);
        }
    }

    /**
     * Wrapper method to route to either a) Create the AEM tag or b) Update Tag properties. Error is swallowed and logged.
     * AEM Tag translations are persisted via properties of EN tag node. ExL api has separate http calls for each locale.
     *
     * @param tagManager
     * @param parentTag
     * @param exlTag
     * @param locale
     */
    private void createOrUpdateAEMTag(TagManager tagManager, String parentTag, JsonElement exlTag, String locale) {
        String tagName = ((JsonObject) exlTag).getAsJsonPrimitive(nameElementKey).getAsString();
        // if locale == EN then create tag node; else update
        if (locale.equalsIgnoreCase("en")) {
            createAEMTag(tagManager, parentTag, tagName, null);
        } else {
            // non-EN locales are persisted as tag node properties; find corresponding EN tag
            if (((JsonObject) exlTag).getAsJsonPrimitive(exlApiEnglishKey) != null) {
                String englishTagName = ((JsonObject) exlTag).getAsJsonPrimitive(exlApiEnglishKey).getAsString();
                String englishTagID = tagNamespace + StringUtils.lowerCase(parentTag.toLowerCase()) + FW_SLASH + deriveTagID(englishTagName);
                persistTagLocalization(tagManager, englishTagID, exlTag, locale);
            }
        }
    }

    /**
     * Create the AEM tag. Error is swallowed and logged
     *
     * @param tagManager
     * @param parentTag
     * @param tagName
     * @param tagHierarchy
     */
    private void createAEMTag(TagManager tagManager, String parentTag, String tagName, String tagHierarchy) {
        String optionalTagHierarchy = "";
        if (StringUtils.isNotBlank(tagHierarchy)) {
            optionalTagHierarchy = deriveTagID(tagHierarchy) + FW_SLASH;
            // EXLM-720: create the hierarchy tag first, so it has a proper jcr:title
            // ex: given exl/feature/campaign/subscriptions, below will create a proper exl/feature/campaign tag node
            String tagHierarchyId = tagNamespace + StringUtils.lowerCase(parentTag) + FW_SLASH + deriveTagID(tagHierarchy);
            try {
                Tag hierarchyTag = tagManager.createTag(tagHierarchyId, tagHierarchy, parentTag + FW_SLASH + tagHierarchy);
            } catch (InvalidTagFormatException e) {
                LOGGER.error("Error while creating tag {} : {}", tagHierarchyId, e.getMessage());
            }
        }

        // derive cq:tag node
        String tagId = tagNamespace + StringUtils.lowerCase(parentTag) + FW_SLASH + optionalTagHierarchy + deriveTagID(tagName);
        try {
            // we can create the parent tag separately if it needs a jcr:title but unless it's required
            // we let the api create the parent tag automatically but without the title
            Tag tag = tagManager.createTag(tagId, tagName, parentTag + FW_SLASH + tagName);
        } catch (InvalidTagFormatException e) {
            LOGGER.error("Error while creating tag {} : {}", tagId, e.getMessage());
        }
    }

    /**
     * Create the Feature tag for the given Solution tag
     *
     * @param tagManager
     * @param apiURL
     * @param solutionTagElement
     */
    private void createFeatureTags(TagManager tagManager, String apiURL, JsonElement solutionTagElement) throws IOException {
        //        For each solutionName provided as parameter
        //          fetch https://experienceleague.adobe.com/api/features?full=true&page_size=1000&Solution=solutionName
        //          if empty data, do nothing.  ex "Acrobat Sign"
        //          else
        //              create Feature tag(s) for this Solution.      ex. "Document Cloud"
        String solutionName = null;
        //boolean nested = false;

        if (((JsonObject) solutionTagElement).has(nameElementKey)) {
            solutionName = ((JsonObject) solutionTagElement).getAsJsonPrimitive(nameElementKey).getAsString();
        }

        if (StringUtils.isBlank(solutionName)) {
            return;
        }

        // fetch json for each locale, EN should be always first
        List<String[]> tagLocalesList = Arrays.stream(tagLocales).map(s -> s.split(",")).collect(Collectors.toList());
        for (String[] locale : tagLocalesList) {
        //for (String locale : tagLocales) {
            String featureApiURL = apiURL + "&Solution=" + URLEncoder.encode(solutionName, StandardCharsets.UTF_8.toString()) + "&lang=" + locale[0];
            HttpGet httpGet = new HttpGet(featureApiURL);
            RequestConfig config = RequestConfig.custom()
                    .setConnectTimeout(CONNECT_TIMEOUT)
                    .setConnectionRequestTimeout(CONNECT_TIMEOUT)
                    .setSocketTimeout(SOCKET_TIMEOUT)
                    .build();
            httpGet.setConfig(config);

            CloseableHttpResponse response = null;

            response = httpClient.execute(httpGet);
            int statusValidate = response.getStatusLine().getStatusCode();
            LOGGER.info("The status code from url '{}' is '{}'", featureApiURL, statusValidate);
            if (statusValidate == HttpStatus.SC_OK || statusValidate == HttpStatus.SC_NO_CONTENT) {
                try {
                    JsonObject responseJson = EXLUtils.getResponseJson(response);
                    JsonArray exlTagsArray = responseJson.getAsJsonArray("data");
                    for (JsonElement exlTag : exlTagsArray) {
                        String tagName = ((JsonObject) exlTag).getAsJsonPrimitive(nameElementKey).getAsString();
                        if (locale[0].equalsIgnoreCase("en")) {
                            createAEMTag(tagManager, featureTagName, tagName, solutionName);
                        } else {
                            // non-EN locales are persisted as tag node properties; find corresponding EN tag
                            String englishTagName = ((JsonObject) exlTag).getAsJsonPrimitive(exlApiEnglishKey).getAsString();
                            String englishTagID = tagNamespace + StringUtils.lowerCase(featureTagName) + FW_SLASH + deriveTagID(solutionName) + FW_SLASH + deriveTagID(englishTagName);
                            persistTagLocalization(tagManager, englishTagID, exlTag, locale[1]);
                        }
                    }
                } catch (JSONException jsonException) {
                    LOGGER.error("Error while parsing json from url '{}'. Error: {}", featureApiURL, jsonException.getMessage());
                }
            }
        }
    }


    /**
     * Create the Solution tag and child version tags if applicable
     *
     * @param tagManager
     * @param parentTag
     * @param exlTagElement
     */
    private void createAEMSolutionTag(TagManager tagManager, String parentTag, JsonElement exlTagElement) {
        // EXLM-886
        //   Given {nameElementKey: "Experience Manager",versionsElementKey: ["6.4","6.5","Cloud Service"],"source": "solutions", ,..}
        //         will create 6.4, 6.5, Cloud Service tags under Experience Manager parent tag
        //   Given {nameElementKey: "Experience Manager 6.4", nestedElementKey: true, versionsElementKey: ["6.4"], "source": "solutions", ,..}
        //         will be skipped and no tag (Experience Manager 6.4) under Solution will be created

        String solutionName = null;
        boolean nested = false;

        if (((JsonObject) exlTagElement).has(nameElementKey)) {
            solutionName = ((JsonObject) exlTagElement).getAsJsonPrimitive(nameElementKey).getAsString();
        }

        if (StringUtils.isBlank(solutionName)) {
            return;
        }

        if (((JsonObject) exlTagElement).has(nestedElementKey)) {
            JsonElement nestedEl = ((JsonObject) exlTagElement).get(nestedElementKey);
            nested = nestedEl.getAsBoolean();
        }

        if (((JsonObject) exlTagElement).has(versionsElementKey)) {
            JsonArray versionsArr = ((JsonObject) exlTagElement).getAsJsonArray(versionsElementKey);
            if (versionsArr != null && !versionsArr.isEmpty() && !nested) {
                for (JsonElement version : versionsArr) {
                    String versionTag = version.getAsString().replaceAll("\"", "");
                    createAEMTag(tagManager, parentTag, versionTag, solutionName);
                }
            }
        } else {
            createAEMTag(tagManager, parentTag, solutionName, null);
        }
    }

    /**
     * Replicates all pages inside the taxonomy folder.
     * @param resourceResolver The resource resolver.
     */
    public void replicateTaxonomyPages(ResourceResolver resourceResolver) {
        try {
            // Get the taxonomy folder resource
            Resource taxonomyResource = resourceResolver.getResource(TAXONOMY_FOLDER_PATH);
            if (taxonomyResource == null) {
                LOGGER.error("Taxonomy folder not found at path: {}", TAXONOMY_FOLDER_PATH);
                return;
            }
            // Get the PageManager and iterate through all pages in the taxonomy folder
            PageManager pageManager = resourceResolver.adaptTo(PageManager.class);
            if (pageManager != null) {
                Page taxonomyPage = pageManager.getPage(TAXONOMY_FOLDER_PATH);
                if (taxonomyPage != null) {
                    Iterator<Page> pageIterator = taxonomyPage.listChildren();
                    while (pageIterator.hasNext()) {
                        Page childPage = pageIterator.next();
                        replicatePage(childPage.getPath(), resourceResolver);
                    }
                } else {
                    LOGGER.warn("No pages found in the taxonomy folder.");
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error occurred while replicating taxonomy pages: {}", e.getMessage(), e);
        }
    }

    /**
     * Replicates a single page.
     *
     * @param pagePath The path of the page to replicate.
     * @param resourceResolver The resource resolver.
     */
    private void replicatePage(String pagePath, ResourceResolver resourceResolver) throws ReplicationException {
        Session session = resourceResolver.adaptTo(Session.class);
        if (session != null) {
            replicator.replicate(session, ReplicationActionType.ACTIVATE, pagePath);
            LOGGER.info("Successfully replicated page: {}", pagePath);
        } else {
            LOGGER.error("Unable to adapt resource resolver to JCR session.");
        }
    }

    @Override
    public void run() {
        ResourceResolver resolver = null;
        try {
            resolver = resourceResolverFactory.getServiceResourceResolver(AUTH_INFO);
            TagManager tagManager = resolver.adaptTo(TagManager.class);

            /*
              (parent) tagName ,      apiURL,                                   optionalHierarchy, jsonFormat
              "Content-Type,https://experienceleague.adobe.com/api/features?page_size=1000,Solution,no-format",
              "Levels,https://experienceleague.adobe.com/api/levels?page_size=1000,,json-format"
             */
            List<String[]> exlAPIList = Arrays.stream(exlAPIs).map(s -> s.split(",")).filter(a -> StringUtils.isBlank(a[2])).collect(Collectors.toList());
            String[] arrSolutionsAPI = Arrays.stream(exlAPIs).map(s -> s.split(",")).filter(a -> a[2].equals(solutionTagName)).collect(Collectors.toList()).get(0);
            List<String[]> tagLocalesList = Arrays.stream(tagLocales).map(s -> s.split(",")).collect(Collectors.toList());

            for (String[] values : exlAPIList) {
                String parentTagName = values[0];
                String apiURL = values[1];
                String jsonFormat = values[3];

                // fetch json for each locale, EN should be always first
                // each tagLocalesList elements has 2 values; eg. "pt-BR","pt"
                // 1st value is param for Exl api call; 2nd value the ISO2 required by EDS
                for (String[] locale : tagLocalesList) {
                    LOGGER.info("Tag {}: URL {}: Format {}", parentTagName, apiURL, jsonFormat);

                    String apiLocaleURL = apiURL + "&lang=" + locale[0];
                    HttpGet httpGet = new HttpGet(apiLocaleURL);
                    RequestConfig config = RequestConfig.custom()
                            .setConnectTimeout(CONNECT_TIMEOUT)
                            .setConnectionRequestTimeout(CONNECT_TIMEOUT)
                            .setSocketTimeout(SOCKET_TIMEOUT)
                            .build();
                    httpGet.setConfig(config);

                    CloseableHttpResponse response = null;
                    try {
                        response = httpClient.execute(httpGet);
                        int statusValidate = response.getStatusLine().getStatusCode();
                        LOGGER.info("The status code from url '{}' is '{}'", apiURL, statusValidate);
                        if (statusValidate == HttpStatus.SC_OK || statusValidate == HttpStatus.SC_NO_CONTENT) {
                            JsonObject responseJson = EXLUtils.getResponseJson(response);
                            JsonArray exlTagsArray = responseJson.getAsJsonArray("data");
                            for (JsonElement exlTag : exlTagsArray) {
                                if (parentTagName.equals(solutionTagName)) {
                                    createAEMSolutionTag(tagManager, parentTagName, exlTag);
                                    createFeatureTags(tagManager, arrSolutionsAPI[1], exlTag);
                                } else if (jsonFormat.equals("no-format") && !parentTagName.equals(solutionTagName)) {
                                    createAEMTag(tagManager, parentTagName, exlTag.getAsString(), null);
                                } else {
                                    createOrUpdateAEMTag(tagManager, parentTagName, exlTag, locale[1]);
                                }
                            }
                        }

                    } catch (IOException | JSONException e) {
                        LOGGER.error("TagSynch Scheduler Error during http get of {} {}", apiURL, e);
                        throw new RuntimeException(e);
                    }
                }
                // commit for each tag category
                resolver.commit();
            }

            // publish exl tags
            replicateTaxonomyPages(resolver);
            LOGGER.info("Tag Sync Scheduler completed.");
        } catch (LoginException | PersistenceException e) {
            LOGGER.error("Error occurred during while processing Tag Sync Scheduler {}", e.getMessage(), e);
        } finally {
            resolver.close();
        }
    }
}
