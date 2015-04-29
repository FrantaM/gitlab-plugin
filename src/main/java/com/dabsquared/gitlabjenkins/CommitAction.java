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
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.ServletException;

import org.codehaus.jackson.annotate.JsonIgnore;
import org.codehaus.jackson.annotate.JsonProperty;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import com.chikli.hudson.plugin.naginator.NaginatorCause;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import hudson.Plugin;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Fingerprint;
import hudson.model.Fingerprint.RangeSet;
import hudson.model.Job;
import hudson.model.Run;
import hudson.plugins.git.Revision;
import hudson.plugins.git.util.BuildData;
import hudson.security.ACL;
import hudson.tasks.Fingerprinter.FingerprintAction;
import hudson.util.HttpResponses;
import hudson.util.ReflectionUtils;

import jenkins.model.Jenkins;
import jenkins.security.NotReallyRoleSensitiveCallable;

/**
 * Gitlab actions/info related to a commit.
 *
 * @author Franta Mejta
 * @sa.date 2015-03-09T13:15:09+0100
 */
@Slf4j
public class CommitAction {

    private static final Comparator<Run<?, ?>> RUN_COMPARATOR = new Comparator<Run<?, ?>>() {

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

    public CommitAction(@Nonnull final Job<?, ?> job, @Nullable final Run<?, ?> run) {
        this.job = job;
        this.run = run;
    }

    public HttpResponse doIndex() {
        final String url = run != null ? run.getUrl() : job.getUrl();
        return HttpResponses.redirectViaContextPath(url);
    }

    public HttpResponse doStatus() {
        JobAction.validateToken(this.job);

        final Status status = new Status();
        status.setStatus(StatusImage.forRun(this.run));

        if (run != null) {
            status.setId(run.getNumber());

            final Revision lastrev = run.getAction(BuildData.class).getLastBuiltRevision();
            assert lastrev != null;
            status.setSha(lastrev.getSha1String());

            for (final Run<?, ?> downstream : this.findAllRuns()) {
                status.addRun(downstream);
            }
        }

        return new HttpResponse() {

            @Override
            public void generateResponse(final StaplerRequest req, final StaplerResponse rsp, final Object node) throws IOException, ServletException {
                rsp.setContentType("application/json");
                rsp.getWriter().println(GitLabRootAction.JSON.writeValueAsString(status));
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
                    final Job<?, ?> fpjob = ACL.impersonate(ACL.SYSTEM, new JobByNameCallable(e.getKey()));
                    if (fpjob != null) {
                        final List<Run<?, ?>> fpruns = new ArrayList<Run<?, ?>>();
                        for (final int build : e.getValue().listNumbers()) {
                            final Run<?, ?> fprun = fpjob.getBuildByNumber(build);
                            if (fprun != null) {
                                fpruns.add(run);
                            }
                        }

                        /* #3: Consider only latest naginator-rescheduled downstream run */
                        final Plugin naginator = Jenkins.getActiveInstance().getPlugin("naginator");
                        if (naginator != null && naginator.getWrapper().isActive()) {
                            for (final Iterator<Run<?, ?>> i = fpruns.iterator(); i.hasNext();) {
                                final Run<?, ?> fprun = i.next();
                                for (final Run<?, ?> remaining : fpruns) {
                                    final Integer ngc = this.getNaginatorCause(remaining);
                                    if (ngc != null && ngc.equals(fprun.getNumber())) {
                                        i.remove();
                                        break;
                                    }
                                }
                            }
                        }

                        for (final Run<?, ?> fprun : fpruns) {
                            if (list.add(fprun)) {
                                this.findDownstreamRuns(list, fprun);
                            }
                        }
                    }
                }
            }
        }

        return list;
    }

    @Nullable
    private Integer getNaginatorCause(@Nonnull final Run<?, ?> run) {
        for (final CauseAction ca : run.getActions(CauseAction.class)) {
            for (final Cause c : ca.getCauses()) {
                if (c instanceof NaginatorCause) {
                    try {
                        final Field field = ReflectionUtils.findField(c.getClass(), "summary");
                        ReflectionUtils.makeAccessible(field);

                        final String summary = String.valueOf(field.get(c));
                        if (summary.startsWith("#")) {
                            return Integer.valueOf(summary.substring(1));
                        } else {
                            log.debug("summary field in NaginatorCause does not "
                                      + "contain build number. value={}", summary);
                        }
                    } catch (final SecurityException ex) {
                        log.warn("cannot access field in NaginatorCause", ex);
                    } catch (final ReflectiveOperationException ex) {
                        log.warn("cannot access field in NaginatorCause", ex);
                    } catch (final NumberFormatException ex) {
                        log.warn("summary field in NaginatorCause has unexpected value", ex);
                    }
                }
            }
        }

        return null;
    }

    public void doDynamic(final StaplerRequest req, final StaplerResponse rsp) throws IOException, ServletException {
        if (req.getRestOfPath().startsWith("/status")) {
            rsp.forward(this, "status", req);
        }
    }

    @Data
    private static final class Status {

        private Integer id;
        @JsonIgnore
        private StatusImage status;
        private Double coverage;
        private String sha;
        private List<String> builds;

        public void addRun(final Run<?, ?> run) {
            final StatusImage si = StatusImage.forRun(run);
            this.status = this.status == null ? si : StatusImage.moreImportant(this.status, si);

            if (this.builds == null) {
                this.builds = new ArrayList<String>();
            }
            this.builds.add(run.getFullDisplayName());
        }

        @JsonProperty("status")
        public final String getStatusText() {
            return this.getStatus().asText();
        }

    }

    @RequiredArgsConstructor
    public static final class JobByNameCallable extends NotReallyRoleSensitiveCallable<Job<?, ?>, RuntimeException> {

        private static final long serialVersionUID = 1L;
        @Nonnull
        private final String jobName;

        @Override
        public Job<?, ?> call() {
            return Jenkins.getActiveInstance().getItemByFullName(this.jobName, Job.class);
        }

    }

}
