/*
 * The MIT License
 *
 * Copyright 2015 Franta Mejta
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.dabsquared.gitlabjenkins;

import java.io.IOException;

import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.JsonProcessingException;
import org.codehaus.jackson.Version;
import org.codehaus.jackson.map.DeserializationConfig;
import org.codehaus.jackson.map.DeserializationContext;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.PropertyNamingStrategy;
import org.codehaus.jackson.map.ext.JodaDeserializers;
import org.codehaus.jackson.map.module.SimpleModule;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.DateTimeFormatterBuilder;
import org.joda.time.format.ISODateTimeFormat;
import org.kohsuke.stapler.StaplerProxy;

import lombok.extern.slf4j.Slf4j;

import hudson.Extension;
import hudson.model.UnprotectedRootAction;

import jenkins.model.Jenkins;

/**
 *
 * @author Franta Mejta
 * @sa.date 2015-03-06T10:26:06+0100
 */
@Extension @Slf4j
public class GitLabRootAction implements UnprotectedRootAction, StaplerProxy {

    public static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationConfig.Feature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setPropertyNamingStrategy(PropertyNamingStrategy.CAMEL_CASE_TO_LOWER_CASE_WITH_UNDERSCORES)
            .withModule(new SimpleModule("GitLabPlugin", new Version(1, 0, 0, null)) {

                {
                    addDeserializer(DateTime.class, new JodaDeserializers.DateTimeDeserializer<DateTime>(DateTime.class) {

                        private final DateTimeFormatter fmt = new DateTimeFormatterBuilder()
                                .append(ISODateTimeFormat.date())
                                .appendLiteral(' ')
                                .append(ISODateTimeFormat.hourMinuteSecond())
                                .appendLiteral(' ')
                                .appendTimeZoneOffset("UTC", true, 2, 4)
                                .toFormatter();

                        @Override
                        public DateTime deserialize(final JsonParser jp, final DeserializationContext ctxt) throws IOException, JsonProcessingException {
                            try {
                                return super.deserialize(jp, ctxt);
                            } catch (final IllegalArgumentException ex) {
                                return fmt.parseDateTime(jp.getText().trim());
                            }
                        }

                    });
                }

            });
    public static final String URL_NAME = "gitlab-ci";

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return null;
    }

    @Override
    public String getUrlName() {
        return GitLabRootAction.URL_NAME;
    }

    @Override
    public Object getTarget() {
        log.debug("Request received.");
        return this;
    }

    public JobAction getJob(final String name) {
        return JobAction.findJob(Jenkins.getInstance(), name);
    }

}
