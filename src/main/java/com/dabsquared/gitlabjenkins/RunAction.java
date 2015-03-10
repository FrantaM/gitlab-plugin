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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.ServletException;

import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import net.sf.json.JSONNull;
import net.sf.json.JSONObject;

import hudson.model.Fingerprint;
import hudson.model.Fingerprint.RangeSet;
import hudson.model.Job;
import hudson.model.Run;
import hudson.plugins.git.Revision;
import hudson.plugins.git.util.BuildData;
import hudson.tasks.Fingerprinter.FingerprintAction;
import hudson.util.HttpResponses;

import jenkins.model.Jenkins;

/**
 * Gitlab actions/info related to a run.
 *
 * @author Franta Mejta
 * @sa.date 2015-03-09T13:15:09+0100
 */
public class RunAction {

    private static Comparator<Run<?, ?>> RUN_COMPARATOR = new Comparator<Run<?, ?>>() {

        @Override
        public int compare(final Run<?, ?> o1, final Run<?, ?> o2) {
            int d = o1.getParent().getFullName().compareTo(o2.getParent().getFullName());
            if (d == 0) {
                d = o1.getNumber() - o2.getNumber();
            }
            return d;
        }

    };
    /**
     * Job of {@link #run}.
     * Needed because the run may be null.
     */
    @Nonnull
    private final Job<?, ?> job;
    /**
     * Root build of specified commit.
     * Null means that that commit wasn't build.
     */
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
        StatusImage overallStatus = StatusImage.forRun(this.run);

        if (run != null) {
            json.element("id", run.getNumber());
            json.element("coverage", JSONNull.getInstance());

            final Revision lastrev = run.getAction(BuildData.class).getLastBuiltRevision();
            assert lastrev != null;
            json.element("sha", lastrev.getSha1String());

            for (final Run<?, ?> downstream : this.findAllRuns()) {
                json.accumulate("builds", downstream.getFullDisplayName());
                overallStatus = StatusImage.moreImportant(overallStatus, StatusImage.forRun(downstream));
            }
        }

        json.element("status", overallStatus.asText());

        return new HttpResponse() {

            @Override
            public void generateResponse(final StaplerRequest req, final StaplerResponse rsp, final Object node) throws IOException, ServletException {
                rsp.setContentType("application/json");
                rsp.getWriter().println(json.toString());
            }

        };
    }

    private SortedSet<Run<?, ?>> findAllRuns() {
        final SortedSet<Run<?, ?>> list = new TreeSet<Run<?, ?>>(RUN_COMPARATOR);
        if (this.run != null) {
            list.add(this.run);
            list.addAll(this.findDownstreamRuns(list, this.run));
        }

        return list;
    }

    private SortedSet<Run<?, ?>> findDownstreamRuns(final SortedSet<Run<?, ?>> list, final Run<?, ?> run) {
        for (final FingerprintAction fa : run.getActions(FingerprintAction.class)) {
            for (final Fingerprint fp : fa.getFingerprints().values()) {
                for (final Map.Entry<String, RangeSet> e : fp.getUsages().entrySet()) {
                    final Job<?, ?> fpjob = Jenkins.getActiveInstance().getItemByFullName(e.getKey(), Job.class);
                    if (fpjob != null) {
                        for (final int build : e.getValue().listNumbers()) {
                            final Run<?, ?> fprun = fpjob.getBuildByNumber(build);
                            if (fprun != null) {
                                if (list.add(fprun)) {
                                    this.findDownstreamRuns(list, fprun);
                                }
                            }
                        }
                    }
                }
            }
        }

        return list;
    }

    public void doDynamic(final StaplerRequest req, final StaplerResponse rsp) throws IOException, ServletException {
        if (req.getRestOfPath().startsWith("/status")) {
            rsp.forward(this, "status", req);
        }
    }

}
