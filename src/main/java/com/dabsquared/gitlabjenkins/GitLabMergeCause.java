package com.dabsquared.gitlabjenkins;

import java.io.File;
import java.io.IOException;

import hudson.triggers.SCMTrigger;

/**
 * Created by daniel on 6/8/14.
 */
public class GitLabMergeCause extends SCMTrigger.SCMTriggerCause {

    private GitLabMergeRequest mergeRequest;

    public GitLabMergeCause(GitLabMergeRequest mergeRequest) {
        this.mergeRequest = mergeRequest;
    }

    public GitLabMergeCause(GitLabMergeRequest mergeRequest, File logFile) throws IOException {
        super(logFile);
        this.mergeRequest = mergeRequest;
    }

    public GitLabMergeCause(GitLabMergeRequest mergeRequest, String pollingLog) {
        super(pollingLog);
        this.mergeRequest = mergeRequest;
    }

    public GitLabMergeRequest getMergeRequest() {
        return mergeRequest;
    }

    @Override
    public String getShortDescription() {
        return "GitLab Merge Request #" + this.mergeRequest.getObjectAttribute().getIid() + " : " + this.mergeRequest.getObjectAttribute().getSourceBranch()
               + " => " + this.mergeRequest.getObjectAttribute().getTargetBranch();
    }

}
