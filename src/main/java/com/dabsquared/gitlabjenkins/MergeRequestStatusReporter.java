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

import org.gitlab.api.GitlabAPI;
import org.gitlab.api.models.GitlabMergeRequest;
import org.gitlab.api.models.GitlabProject;
import org.kohsuke.stapler.DataBoundConstructor;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;

import jenkins.tasks.SimpleBuildStep;

/**
 * @author Franta Mejta
 * @sa.date 2015-03-16T14:31:20+0100
 */
public class MergeRequestStatusReporter extends Notifier implements SimpleBuildStep {

    @DataBoundConstructor
    public MergeRequestStatusReporter() {
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public void perform(final Run<?, ?> run, final FilePath workspace, final Launcher launcher, final TaskListener listener) throws InterruptedException, IOException {
        final GitLabMergeCause mrc = run.getCause(GitLabMergeCause.class);
        if (mrc == null) {
            return;
        }

        final String message = run.getResult() == Result.SUCCESS ? ":+1:" : ":-1:";

        final GitLabTrigger.DescriptorImpl d = GitLabTrigger.all().get(GitLabTrigger.DescriptorImpl.class);
        final GitlabAPI api = d.newGitlabConnection();
        if (api != null) {
            try {
                final GitlabProject project = api.getProject(mrc.getTargetProjectId());
                final GitlabMergeRequest mr = api.getMergeRequest(project, mrc.getRequestId());
                api.createNote(mr, message);
            } catch (final IOException ex) {
                ex.printStackTrace(listener.error(Messages.MergeRequestStatusReporter_CannotAddNote(mrc.getRequestId())));
            }
        }
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        @Override @SuppressWarnings("rawtypes")
        public boolean isApplicable(final Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return Messages.MergeRequestStatusReporter_DisplayName();
        }

    }

}
