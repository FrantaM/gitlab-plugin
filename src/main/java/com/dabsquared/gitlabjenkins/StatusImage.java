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
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.ServletException;

import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import hudson.model.Result;
import hudson.model.Run;

import jenkins.model.Jenkins;

/**
 * @author Franta Mejta
 * @sa.date 2015-03-06T11:11:29+0100
 */
public enum StatusImage {

    UNKNOWN, UNSTABLE, RUNNING, SUCCESS, FAILED;

    @Nonnull
    public static StatusImage forRun(@Nullable final Run<?, ?> run) {
        if (run == null) {
            return StatusImage.UNKNOWN;
        }
        if (run.isBuilding()) {
            return StatusImage.RUNNING;
        }

        final Result result = run.getResult();
        assert result != null;

        if (result.isBetterOrEqualTo(Result.SUCCESS)) {
            return StatusImage.SUCCESS;
        }
        if (result.isBetterOrEqualTo(Result.UNSTABLE)) {
            return StatusImage.UNSTABLE;
        }
        if (result.isBetterOrEqualTo(Result.FAILURE)) {
            return StatusImage.FAILED;
        }

        return StatusImage.UNKNOWN;
    }

    public URL getLocalUrl() {
        try {
            final URL base = Jenkins.getActiveInstance().getPlugin(GitLabPlugin.class).getWrapper().baseResourceURL;
            return new URL(base.toExternalForm() + "/images/" + this.name().toLowerCase(Locale.ROOT) + ".png");
        } catch (final MalformedURLException ex) {
            throw new AssertionError(ex);
        }
    }

    public String asText() {
        if (this == UNSTABLE) {
            /* There isn't an unstable state in gitlab atm. */
            return FAILED.asText();
        }
        if (this == UNKNOWN) {
            return "pending";
        }
        return this.name().toLowerCase(Locale.ROOT);
    }

    public HttpResponse respond() {
        return new HttpResponse() {

            @Override
            public void generateResponse(StaplerRequest req, StaplerResponse rsp, Object node) throws IOException, ServletException {
                final URLConnection con = getLocalUrl().openConnection();
                final InputStream in = con.getInputStream();
                try {
                    rsp.serveFile(req, in, 0, con.getContentLength(), con.getURL().toString());
                } finally {
                    in.close();
                }
            }

        };
    }

}
