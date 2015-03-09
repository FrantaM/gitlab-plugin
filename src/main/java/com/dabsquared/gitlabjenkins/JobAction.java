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

import org.eclipse.jgit.transport.RemoteConfig;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

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
        return job != null ? new JobAction(job) : null;
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

    @Nullable
    public RunAction getCommits(final String hash) {
        final Run<?, ?> run = this.findRunByHash(hash);
        return run != null ? new RunAction(run) : null;
    }

    public HttpResponse doStatus(@QueryParameter final String ref) {
        return StatusImage.forRun(findRunByRef(ref)).respond();
    }

    public void doDynamic(final StaplerRequest req, final StaplerResponse rsp) throws IOException, ServletException {
        if (req.getRestOfPath().startsWith("/status")) {
            rsp.forward(this, "status", req);
        }
    }

}
