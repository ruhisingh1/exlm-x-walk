package com.exlm.core.schedulers;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(name = "Tag Synch Scheduler Configuration", description = "Tag Synch Scheduler Configuration")
public @interface TagSynchSchedulerConfig {

    @AttributeDefinition(name = "Scheduler name", description = "Scheduler name", type = AttributeType.STRING)
    public String schedulerName() default "Tag Synch Scheduler";

    @AttributeDefinition(name = "Run Concurrently?", description = "Schedule task concurrently", type = AttributeType.BOOLEAN)
    boolean schedulerConcurrent() default false;

    @AttributeDefinition(name = "Enabled", description = "Enable Scheduler", type = AttributeType.BOOLEAN)
    boolean serviceEnabled() default true;

    @AttributeDefinition(name = "Run On", description = "Run on lead cluster", type = AttributeType.BOOLEAN)
    boolean runOnLeader() default true;

    @AttributeDefinition(name = "Cron Expression", description = "Default: run every two minute.", type = AttributeType.STRING)
    String schedulerExpression() default "0 0/5 * 1/1 * ? *";

    @AttributeDefinition(name = "EXL Tag APIs", description = "Endpoints for EXL Tags")
    String[] exlTagApis() default { "", "", "" };

    @AttributeDefinition(name = "EXL Tag Locales", description = "Locales for EXL Tags")
    String[] tagLocales() default { "en", "en" };

}