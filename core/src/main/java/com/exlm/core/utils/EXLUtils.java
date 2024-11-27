package com.exlm.core.utils;

import com.google.gson.*;
import org.apache.http.HttpHost;
import org.apache.http.ParseException;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.client.LaxRedirectStrategy;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.commons.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

/**
 *
 */
public class EXLUtils  {

	private static final Logger LOGGER = LoggerFactory.getLogger(EXLUtils.class);

	private static final int CONNECT_TIMEOUT = 10000;
	private static final int SOCKET_TIMEOUT = 30000;
	private static final int DEFAULT_CONNECTIONS_MAX_PER_ROUTE = 20;
	public static final int CONNECTIONS_MAX = 20;

	private static final String tagNamespace = "exl:";

	public static final String EXL_SERVICE_USER = "exl-service-user";

	public static final Map<String, Object> AUTH_INFO =
			Collections.<String, Object>singletonMap(ResourceResolverFactory.SUBSERVICE, EXL_SERVICE_USER);


	/**
	 * Get CloseableHttpClient
	 *
	 * @param defaultConnectionsMaxPerRoute
	 * @param connectionsMax
	 * @return CloseableHttpClient
	 */
	public static CloseableHttpClient getCloseableHttpClient(int defaultConnectionsMaxPerRoute, int connectionsMax) {
		PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager(
				RegistryBuilder.<ConnectionSocketFactory>create()
						.register(HttpHost.DEFAULT_SCHEME_NAME, PlainConnectionSocketFactory.getSocketFactory())
						.register(HttpHost.DEFAULT_SCHEME_NAME+"s", SSLConnectionSocketFactory.getSocketFactory())
						.build()
		);
		connManager.setDefaultMaxPerRoute(defaultConnectionsMaxPerRoute);
		connManager.setMaxTotal(connectionsMax);
		RequestConfig requestConfig = RequestConfig.custom()
				.setConnectTimeout(CONNECT_TIMEOUT)
				.setSocketTimeout(SOCKET_TIMEOUT)
				.build();
		return HttpClients.custom()
				.useSystemProperties().setDefaultRequestConfig(requestConfig)
				.setRedirectStrategy(new LaxRedirectStrategy())
				.setConnectionManager(connManager)
				.setMaxConnTotal(connectionsMax)
				.build();
	}

	/**
	 * Wrapper get HttpClient
	 *
	 * @return CloseableHttpClient
	 */
	public static CloseableHttpClient getHttpClient() {
		return getCloseableHttpClient(DEFAULT_CONNECTIONS_MAX_PER_ROUTE, CONNECTIONS_MAX);
	}

	/**
	 * Extracts JSON from HTTP Response.
	 *
	 * @param response
	 * @return
	 * @throws IOException
	 * @throws JSONException
	 */
	public static JsonObject getResponseJson(CloseableHttpResponse response) throws IOException, JSONException {
		if (response == null || response.getEntity() == null) {
			return new JsonObject();
		}
		// remove content type check as the content type is 'application/vnd.adobecloud.events+json' for journaling api
		String text = null;
		try {
			text = EntityUtils.toString(response.getEntity());
			JsonObject result = new JsonParser().parseString(text).getAsJsonObject();
			EntityUtils.consume(response.getEntity());
			return result;
		} catch (IOException e) {
			LOGGER.error("Error while consuming response.getEntity() {}", e.getMessage());
			throw new IOException();
		} catch (ParseException err) {
			LOGGER.error("Error while parsing json object {}", err.getMessage());
			throw new JSONException(err);
		}
	}

}
