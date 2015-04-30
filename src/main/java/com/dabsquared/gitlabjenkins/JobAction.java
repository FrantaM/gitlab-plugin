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
import java.io.InputStreamReader;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jackson.JsonNode;
import org.eclipse.jgit.transport.RemoteConfig;
import org.gitlab.api.GitlabAPI;
import org.gitlab.api.models.GitlabMergeRequest;
import org.gitlab.api.models.GitlabProject;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses.HttpResponseException;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import com.dabsquared.gitlabjenkins.models.attrs.GitlabMergeRequestHookAttrs;
import com.dabsquared.gitlabjenkins.models.hooks.GitlabMergeRequestHook;
import com.dabsquared.gitlabjenkins.models.hooks.GitlabPushHook;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.collect.Iterables;
import com.google.common.io.CharStreams;

import lombok.extern.slf4j.Slf4j;

import hudson.Util;
import hudson.model.AbstractProject;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.Run;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.Revision;
import hudson.plugins.git.util.Build;
import hudson.plugins.git.util.BuildData;
import hudson.security.ACL;
import hudson.util.HttpResponses;

import jenkins.model.ParameterizedJobMixIn;

/**
 * Gitlab actions/info related to a job.
 *
 * @author Franta Mejta
 * @sa.date 2015-03-09T13:13:54+0100
 */
@Slf4j
public class JobAction {

    /**
     * Context of this JobAction.
     *
     * May be a job itself or ItemGroup (ie Folder)
     * holding other jobs.
     */
    @Nonnull
    private final Item ctx;

    public JobAction(final Item ctx) {
        this.ctx = ctx;
    }

    @Nullable
    static JobAction findJob(final Object ctx, final String name) {
        final Item[] holder = new Item[1];
        ACL.impersonate(ACL.SYSTEM, new Runnable() {

            @Override
            public void run() {
                if (ctx instanceof ItemGroup<?>) {
                    holder[0] = ((ItemGroup<?>) ctx).getItem(name);
                }
            }

        });

        final Item job = holder[0];

        /* Filter out jobs w/out gitlab trigger */
        if (job instanceof Job<?, ?>) {
            findTrigger((Job<?, ?>) job);
        }

        if (job != null) {
            log.debug("Found item {} for name {}", job.getFullName(), name);
            return new JobAction(job);
        } else {
            log.debug("Found no item for name {}", name);
            return null;
        }
    }

    /**
     * Returns gitlab trigger of supplied job, if configured.
     *
     * @param job
     * @return Gitlab trigger
     * @throws HttpResponseException HTTP/404 Trigger not found.
     */
    @Nonnull
    private static GitLabTrigger findTrigger(final Job<?, ?> job) {
        GitLabTrigger trigger = null;
        if (job instanceof ParameterizedJobMixIn.ParameterizedJob) {
            final ParameterizedJobMixIn.ParameterizedJob pj = (ParameterizedJobMixIn.ParameterizedJob) job;
            trigger = Iterables.getFirst(Util.filter(pj.getTriggers().values(), GitLabTrigger.class), null);
        }
        if (trigger == null) {
            throw HttpResponses.notFound();
        }

        return trigger;
    }

    /**
     * Validates access token in current request against
     * the one in job configuration.
     *
     * @param ctx Current Job or Run.
     * @throws HttpResponseException HTTP/403 Access token empty or mismatch.
     * @throws HttpResponseException HTTP/404 Gitlab trigger not configured.
     * @throws IllegalArgumentException Unsupported input parameter.
     */
    static void validateToken(@Nonnull Object ctx) {
        if (ctx instanceof Run<?, ?>) {
            ctx = ((Run<?, ?>) ctx).getParent();
        }
        if (ctx instanceof Job<?, ?>) {
            final String actualToken = Util.fixEmptyAndTrim(Stapler.getCurrentRequest().getParameter("token"));
            final String expectedToken = Util.fixEmptyAndTrim(JobAction.findTrigger((Job<?, ?>) ctx).getToken());
            if (actualToken == null || !actualToken.equals(expectedToken)) {
                throw HttpResponses.forbidden();
            }
            return;
        }
        throw new IllegalArgumentException();
    }

    protected Job<?, ?> asJob() {
        if (this.ctx instanceof Job<?, ?>) {
            return (Job<?, ?>) this.ctx;
        }
        throw HttpResponses.notFound();
    }

    @Nullable
    private Run<?, ?> findRunByRef(@Nonnull final String ref) {
        return this.findRun(ref, null);
    }

    @Nullable
    private Run<?, ?> findRunByHash(@Nonnull final String hash) {
        return this.findRun(null, hash);
    }

    @Nullable
    protected Run<?, ?> findRun(@Nullable final String ref, @Nullable final String hash) {
        if (ref == null && hash == null) {
            throw new IllegalArgumentException("ref or hash must not be null");
        }

        String refrepository0 = "";
        if (ref != null) {
            if (asJob() instanceof AbstractProject<?, ?>) {
                final AbstractProject<?, ?> p = (AbstractProject<?, ?>) asJob();
                if (p.getScm() instanceof GitSCM) {
                    final GitSCM scm = (GitSCM) p.getScm();
                    for (final RemoteConfig rc : scm.getRepositories()) {
                        refrepository0 = rc.getName() + "/";
                        break;
                    }
                }
            }
        }

        final String refrepository = refrepository0;
        final Function<Build, Boolean> valid = new Function<Build, Boolean>() {

            @Override
            public Boolean apply(@Nonnull final Build input) {
                return apply(input.getRevision()) || apply(input.getMarked());
            }

            public Boolean apply(@Nonnull final Revision rev) {
                if (ref != null) {
                    if (!rev.containsBranchName(refrepository + ref)
                        && !rev.containsBranchName("refs/remotes/" + refrepository + ref)) {
                        return false;
                    }
                }
                if (hash != null) {
                    if (!rev.getSha1String().equalsIgnoreCase(hash)) {
                        return false;
                    }
                }
                return true;
            }

        };

        for (final Run<?, ?> run : asJob().getBuilds()) {
            for (final BuildData buildData : run.getActions(BuildData.class)) {
                for (final Build build : buildData.getBuildsByBranchName().values()) {
                    if (valid.apply(build)) {
                        return asJob().getBuildByNumber(build.getBuildNumber());
                    }
                }
            }
        }

        return null;
    }

    @Nullable
    public JobAction getJob(final String name) {
        return JobAction.findJob(this.ctx, name);
    }

    @Nonnull
    public CommitAction getCommits(final String hash) {
        final Run<?, ?> run = this.findRunByHash(hash);
        return new CommitAction(asJob(), run);
    }

    @Nonnull
    public JobAction getRefs(final String ref) {
        return new RefsAdapter(ctx, ref);
    }

    public HttpResponse doIndex() {
        return HttpResponses.redirectViaContextPath(asJob().getUrl());
    }

    public HttpResponse doStatus(@QueryParameter final String ref) {
        return StatusImage.forRun(findRunByRef(ref)).respond();
    }

    public HttpResponse doBuild(final StaplerRequest req) throws IOException {
        final GitLabTrigger trigger = findTrigger(this.asJob());
        final String encoding = Objects.firstNonNull(req.getCharacterEncoding(), Charsets.UTF_8.name());
        final String payload = CharStreams.toString(new InputStreamReader(req.getInputStream(), encoding));
        final JsonNode json = GitLabRootAction.JSON.readTree(payload);

        try {
            JobAction.validateToken(this.ctx);
        } catch (final RuntimeException ex) {
            log.debug("Provided access token is not valid.");
            throw ex;
        }

        final String objectKind = Objects.firstNonNull(Util.fixEmpty(json.path("object_kind").asText()), GitlabPushHook.OBJECT_KIND);
        log.debug("Received event of type: {}", objectKind);

        if (GitlabPushHook.OBJECT_KIND.equalsIgnoreCase(objectKind)) {
            final GitlabPushHook event = GitLabRootAction.JSON.readValue(json, GitlabPushHook.class);
            if (event.isBranchRemoveEvent()) {
                log.debug("Skipping build of removed branch {}.", event.getRef());
                return HttpResponses.ok();
            }

            final PushToOpenMRPolicy openMRPolicy = trigger.getTriggerOpenMergeRequestOnPush();
            log.debug("Push to open merge requests policy: {}", openMRPolicy);

            switch (openMRPolicy) {
                case BUILD_BRANCH_AND_MR:
                case BUILD_MR:
                    boolean buildBranch = true;
                    final GitlabAPI api = trigger.getDescriptor().newGitlabConnection();
                    if (api != null) {
                        try {
                            final GitlabProject gp = api.getProject(event.getProjectId());
                            for (final GitlabMergeRequest mr : api.getOpenMergeRequests(gp)) {
                                if (event.getRef().endsWith(mr.getSourceBranch())) {
                                    log.info("Building merge request #{} for push to {}.", mr.getIid(), event.getRef());
                                    trigger.run(mr);

                                    if (openMRPolicy == PushToOpenMRPolicy.BUILD_MR) {
                                        buildBranch = false;
                                    }
                                }
                            }
                        } catch (final IOException ex) {
                            log.warn("Error while retrieving open merge requests from gitlab. "
                                     + "Not all (if any) may have been scheduled to build.", ex);
                        }
                    } else {
                        log.info("Gitlab API access not available; will not build open merge "
                                 + "request from branch {} if there are any.", event.getRef());
                    }
                    if (!buildBranch) {
                        log.debug("Skipping build of branch {} because some merge requests "
                                  + "are being build instead.", event.getRef());
                        break;
                    }
                case BUILD_BRANCH:
                    if (!event.isTagEvent()) {
                        trigger.run(event);
                    } else {
                        log.info("Skipping build of tag {}.", event.getRef());
                    }
            }
        } else if (GitlabMergeRequestHook.OBJECT_KIND.equalsIgnoreCase(objectKind)) {
            final GitlabMergeRequestHook event = GitLabRootAction.JSON.readValue(json, GitlabMergeRequestHook.class);
            trigger.run(event);
        } else {
            throw HttpResponses.status(HttpServletResponse.SC_BAD_REQUEST);
        }

        return HttpResponses.ok();
    }

    public void doDynamic(final StaplerRequest req, final StaplerResponse rsp) throws IOException, ServletException {
        if (req.getRestOfPath().startsWith("/status")) {
            rsp.forward(this, "status", req);
        }
    }

    public static class RefsAdapter extends JobAction {

        private final String ref;

        public RefsAdapter(final Item ctx, final String ref) {
            super(ctx);
            this.ref = ref;
        }

        @Override
        public JobAction getJob(final String name) {
            throw HttpResponses.notFound();
        }

        @Override
        public JobAction getRefs(final String ref) {
            throw HttpResponses.notFound();
        }

        @Override
        public CommitAction getCommits(final String hash) {
            return new CommitAction(asJob(), findRun(ref, hash));
        }

    }

}
