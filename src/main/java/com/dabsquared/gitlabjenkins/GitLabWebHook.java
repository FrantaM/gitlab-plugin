package com.dabsquared.gitlabjenkins;

import java.io.IOException;
import java.util.Iterator;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;

import lombok.extern.java.Log;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.ItemGroup;
import hudson.model.UnprotectedRootAction;
import hudson.security.ACL;
import hudson.security.csrf.CrumbExclusion;
import hudson.util.HttpResponses;

import jenkins.model.Jenkins;

/**
 * @author Daniel Brooks
 */
@Log @Extension @Deprecated @Restricted(DoNotUse.class)
public class GitLabWebHook implements UnprotectedRootAction {

    public static final String WEBHOOK_URL = "project";

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return null;
    }

    @Override
    public String getUrlName() {
        return WEBHOOK_URL;
    }

    public void doDynamic(final StaplerRequest req, final StaplerResponse rsp) throws ServletException, IOException {
        final Iterator<String> restOfPathParts = Splitter.on('/').omitEmptyStrings().split(req.getRestOfPath()).iterator();
        final AbstractProject<?, ?>[] projectHolder = new AbstractProject<?, ?>[] { null };
        ACL.impersonate(ACL.SYSTEM, new Runnable() {

            @Override
            public void run() {
                final Jenkins jenkins = Jenkins.getActiveInstance();
                Object item = jenkins;
                while (item instanceof ItemGroup<?> && !(item instanceof AbstractProject<?, ?>) && restOfPathParts.hasNext()) {
                    item = jenkins.getItem(restOfPathParts.next(), (ItemGroup<?>) item);
                }
                if (item instanceof AbstractProject<?, ?>) {
                    projectHolder[0] = (AbstractProject<?, ?>) item;
                }
            }

        });

        final AbstractProject<?, ?> project = projectHolder[0];
        if (project == null) {
            throw HttpResponses.notFound();
        } else {
            log.severe(String.format("Old hook is still used by %s (project %s).", req.getRemoteHost(), project.getFullName()));
            final Object newAction = Jenkins.getActiveInstance().getDynamic(GitLabRootAction.URL_NAME);
            rsp.forward(newAction, project.getUrl() + Joiner.on('/').join(restOfPathParts), req);
        }
    }

    @Extension
    public static class GitlabWebHookCrumbExclusion extends CrumbExclusion {

        @Override
        public boolean process(HttpServletRequest req, HttpServletResponse resp, FilterChain chain) throws IOException, ServletException {
            String pathInfo = req.getPathInfo();
            if (pathInfo != null && pathInfo.startsWith(getExclusionPath())) {
                chain.doFilter(req, resp);
                return true;
            }
            return false;
        }

        private String getExclusionPath() {
            return '/' + WEBHOOK_URL + '/';
        }

    }

}
