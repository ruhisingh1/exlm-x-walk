package com.exlm.core.service;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@Component(service = TranslationWorkflowConfig.class, immediate = true)
@Designate(ocd = TranslationWorkflowConfig.Config.class)
public class TranslationWorkflowConfig {

	private static final String DEFAULT_ENVIRONMENT_TYPE = "non-prod";

	private String environmentType;

	private String[] destinationLanguages;

	@ObjectClassDefinition(name = "Automatic Translation Configuration")
	public @interface Config {
		@AttributeDefinition(name = "Environment Type", description = "Should be non-prod/prod - default is non-prod", type = AttributeType.STRING)
		String environment_type() default DEFAULT_ENVIRONMENT_TYPE;

		@AttributeDefinition(name = "Destination Languages", description = "Destination language(s) for translation", type = AttributeType.STRING)
		String[] destinationLanguages() default { "de", "es", "fr", "it", "ja", "ko", "nl", "pt-BR", "sv", "zh-TW",
				"zh-CN", "ar" };
	}

	@Activate
	@Modified
	protected void activate(Config config) {
		environmentType = config.environment_type();
		destinationLanguages = config.destinationLanguages();
	}

	public String getEnvironmentType() {
		return environmentType;
	}

	public String[] getDestinationLanguages() {
		return destinationLanguages;
	}
}
