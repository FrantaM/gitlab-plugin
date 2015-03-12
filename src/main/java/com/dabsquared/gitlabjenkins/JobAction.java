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
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses.HttpResponseException;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import com.dabsquared.gitlabjenkins.models.hooks.GitlabMergeRequestHook;
import com.dabsquared.gitlabjenkins.models.hooks.GitlabPushHook;
import com.google.common.base.Charsets;
import com.google.common.base.Objects;
import com.google.common.collect.Iterables;
import com.google.common.io.CharStreams;

import hudson.Util;
import hudson.model.AbstractProject;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.Run;
import hudson.plugins.git.GitSCM;
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

        return job != null ? new JobAction(job) : null;
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

    private Job<?, ?> asJob() {
        if (this.ctx instanceof Job<?, ?>) {
            return (Job<?, ?>) this.ctx;
        }
        throw HttpResponses.notFound();
    }

    @Nullable
    private Run<?, ?> findRunByRef(@Nullable final String ref) {
        return this.findRun(ref, null);
    }

    @Nullable
    private Run<?, ?> findRunByHash(@Nullable final String hash) {
        return this.findRun(null, hash);
    }

    @Nullable
    private Run<?, ?> findRun(@Nullable final String ref, @Nullable final String hash) {
        String refrepository = "";
        if (ref != null) {
            if (asJob() instanceof AbstractProject<?, ?>) {
                final AbstractProject<?, ?> p = (AbstractProject<?, ?>) asJob();
                if (p.getScm() instanceof GitSCM) {
                    final GitSCM scm = (GitSCM) p.getScm();
                    for (final RemoteConfig rc : scm.getRepositories()) {
                        refrepository = rc.getName() + "/";
                        break;
                    }
                }
            }
        }

        for (final Run<?, ?> run : asJob().getBuilds()) {
            int refBuildNumber = 0;
            for (final BuildData buildData : run.getActions(BuildData.class)) {
                if (ref != null) {
                    final Build buildA = buildData.getLastBuildOfBranch(refrepository + ref);
                    if (buildA != null) {
                        refBuildNumber = Math.max(refBuildNumber, buildA.getBuildNumber());
                    }

                    final Build buildB = buildData.getLastBuildOfBranch("refs/remotes/" + refrepository + ref);
                    if (buildB != null) {
                        refBuildNumber = Math.max(refBuildNumber, buildB.getBuildNumber());
                    }
                }
                if (hash != null) {
                    final Build last = buildData.lastBuild;
                    if (last != null) {
                        if (last.getRevision().getSha1String().startsWith(hash) || last.getMarked().getSha1String().startsWith(hash)) {
                            refBuildNumber = last.getBuildNumber();
                        }
                    }
                }
                if (refBuildNumber > 0) {
                    return asJob().getBuildByNumber(refBuildNumber);
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

    public HttpResponse doStatus(@QueryParameter final String ref) {
        return StatusImage.forRun(findRunByRef(ref)).respond();
    }

    public HttpResponse doBuild(final StaplerRequest req) throws IOException {
        final GitLabTrigger trigger = findTrigger(this.asJob());
        final String encoding = Objects.firstNonNull(req.getCharacterEncoding(), Charsets.UTF_8.name());
        final String payload = CharStreams.toString(new InputStreamReader(req.getInputStream(), encoding));
        final JsonNode json = GitLabRootAction.JSON.readTree(payload);

        JobAction.validateToken(this.ctx);

        final String objectKind = Objects.firstNonNull(Util.fixEmpty(json.path("object_kind").asText()), GitlabPushHook.OBJECT_KIND);
        if (GitlabPushHook.OBJECT_KIND.equalsIgnoreCase(objectKind)) {
            final GitlabPushHook event = GitLabRootAction.JSON.readValue(json, GitlabPushHook.class);
            if (!event.isTagEvent() && trigger.isTriggerOnPush()) {
                trigger.run(event);
            }
        } else if (GitlabMergeRequestHook.OBJECT_KIND.equalsIgnoreCase(objectKind)) {
            final GitlabMergeRequestHook event = GitLabRootAction.JSON.readValue(json, GitlabMergeRequestHook.class);
            if (trigger.isTriggerOnMergeRequest()) {
                trigger.run(event);
            }
        }

        throw HttpResponses.status(HttpServletResponse.SC_BAD_REQUEST);
    }

    public void doDynamic(final StaplerRequest req, final StaplerResponse rsp) throws IOException, ServletException {
        if (req.getRestOfPath().startsWith("/status")) {
            rsp.forward(this, "status", req);
        }
    }

}
