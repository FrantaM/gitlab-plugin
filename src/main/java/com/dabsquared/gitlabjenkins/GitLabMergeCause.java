package com.dabsquared.gitlabjenkins;

import javax.annotation.Nonnull;

import org.gitlab.api.models.GitlabMergeRequest;

import com.dabsquared.gitlabjenkins.models.hooks.GitlabMergeRequestHook;

import lombok.Getter;

import hudson.model.Cause;

@Getter
public class GitLabMergeCause extends Cause {

    private final int targetProjectId;
    private final int requestId;
    private final int requestIId;
    private final String user;
    private final String sourceBranch;
    private final String targetBranch;

    public GitLabMergeCause(@Nonnull final GitlabMergeRequestHook mr) {
        this.targetProjectId = mr.getObjectAttributes().getTargetProjectId();
        this.requestId = mr.getObjectAttributes().getId();
        this.requestIId = mr.getObjectAttributes().getIid();
        this.user = mr.getUser().getName();
        this.sourceBranch = mr.getObjectAttributes().getSourceBranch();
        this.targetBranch = mr.getObjectAttributes().getTargetBranch();
    }

    public GitLabMergeCause(@Nonnull final GitlabMergeRequest mr) {
        this.targetProjectId = mr.getTargetProjectId();
        this.requestId = mr.getId();
        this.requestIId = mr.getIid();
        this.user = mr.getAuthor().getName();
        this.sourceBranch = mr.getSourceBranch();
        this.targetBranch = mr.getTargetBranch();
    }

    @Override
    public String getShortDescription() {
        if (this.user == null) {
            return "Started by GitLab Merge Request";
        }

        return String.format("Started by GitLab Merge Request #%d by %s: %s => %s",
                             this.requestIId, this.user,
                             this.sourceBranch, this.targetBranch);
    }

}
