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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.ServletException;

import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import net.sf.json.JSONNull;
import net.sf.json.JSONObject;

import hudson.model.Job;
import hudson.model.Run;
import hudson.plugins.git.Revision;
import hudson.plugins.git.util.BuildData;
import hudson.util.HttpResponses;

/**
 * Gitlab actions/info related to a run.
 *
 * @author Franta Mejta
 * @sa.date 2015-03-09T13:15:09+0100
 */
public class RunAction {

    @Nonnull
    private final Job<?, ?> job;
    @Nullable
    private final Run<?, ?> run;

    public RunAction(@Nonnull final Job<?, ?> job, @Nullable final Run<?, ?> run) {
        this.job = job;
        this.run = run;
    }

    public HttpResponse doIndex() {
        final String url = run != null ? run.getUrl() : job.getUrl();
        return HttpResponses.redirectViaContextPath(url);
    }

    public HttpResponse doStatus() {
        JobAction.validateToken(this.job);

        final JSONObject json = new JSONObject();
        json.element("status", StatusImage.forRun(this.run).asText());

        if (run != null) {
            json.element("id", run.getNumber());
            json.element("coverage", JSONNull.getInstance());

            final Revision lastrev = run.getAction(BuildData.class).getLastBuiltRevision();
            assert lastrev != null;
            json.element("sha", lastrev.getSha1String());
        }

        return new HttpResponse() {

            @Override
            public void generateResponse(final StaplerRequest req, final StaplerResponse rsp, final Object node) throws IOException, ServletException {
                rsp.setContentType("application/json");
                rsp.getWriter().println(json.toString());
            }

        };
    }

    public void doDynamic(final StaplerRequest req, final StaplerResponse rsp) throws IOException, ServletException {
        if (req.getRestOfPath().startsWith("/status")) {
            rsp.forward(this, "status", req);
        }
    }

}
