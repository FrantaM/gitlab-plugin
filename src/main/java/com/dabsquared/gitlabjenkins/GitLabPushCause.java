package com.dabsquared.gitlabjenkins;

import javax.annotation.Nonnull;

import com.dabsquared.gitlabjenkins.models.hooks.GitlabPushHook;

import hudson.model.Cause;

public class GitLabPushCause extends Cause {

    private final String pushedBy;

    public GitLabPushCause(@Nonnull final GitlabPushHook push) {
        this.pushedBy = push.getUserName();
    }

    @Override
    public String getShortDescription() {
        return String.format("Started by GitLab Push by %s", this.pushedBy);
    }

}
