package com.dabsquared.gitlabjenkins;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.gitlab.api.GitlabAPI;
import org.gitlab.api.models.GitlabBranch;
import org.gitlab.api.models.GitlabMergeRequest;
import org.gitlab.api.models.GitlabProject;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.springframework.util.AntPathMatcher;

import com.dabsquared.gitlabjenkins.models.attrs.GitlabMergeRequestHookAttrs;
import com.dabsquared.gitlabjenkins.models.attrs.GitlabMergeRequestHookAttrs.MergeStatus;
import com.dabsquared.gitlabjenkins.models.attrs.GitlabMergeRequestHookAttrs.State;
import com.dabsquared.gitlabjenkins.models.hooks.GitlabMergeRequestHook;
import com.dabsquared.gitlabjenkins.models.hooks.GitlabPushHook;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.converters.collections.CollectionConverter;
import com.thoughtworks.xstream.converters.reflection.AbstractReflectionConverter;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;
import com.thoughtworks.xstream.mapper.MapperWrapper;

import net.sf.json.JSONObject;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import hudson.Extension;
import hudson.Util;
import hudson.XmlFile;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.AutoCompletionCandidates;
import hudson.model.BuildableItem;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.StringParameterValue;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.RevisionParameterAction;
import hudson.scm.SCM;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.FormValidation;
import hudson.util.XStream2;

import jenkins.model.Jenkins;
import jenkins.triggers.SCMTriggerItem;

/**
 * Triggers a build when we receive a GitLab WebHook.
 *
 * @author Daniel Brooks
 */
@Slf4j
public class GitLabTrigger extends Trigger<BuildableItem> {

    /**
     * Flag whether a push will trigger a build.
     */
    @Getter @Setter @DataBoundSetter
    private boolean triggerOnPush = true;
    /**
     * Flag whether a merge request will trigger a build.
     */
    @Getter @Setter @DataBoundSetter
    private boolean triggerOnMergeRequest = true;
    /**
     * Flag whether a push to a branch with open merge request
     * will trigger a build of that merge request.
     */
    @Getter @Setter @DataBoundSetter
    private boolean triggerOpenMergeRequestOnPush = true;
    /**
     * Branches that will trigger a build on push.
     */
    @Getter @Setter @DataBoundSetter
    private String includeBranchesSpec;
    /**
     * Branches that will not trigger a build on push.
     */
    @Getter @Setter @DataBoundSetter
    private String excludeBranchesSpec;
    /**
     * Access token needed for some actions.
     */
    @Getter @Setter @DataBoundSetter
    private String token;

    @DataBoundConstructor
    public GitLabTrigger() {
    }

    private boolean isBranchAllowed(final String branchName) {
        final List<String> exclude = DescriptorImpl.splitBranchSpec(this.getExcludeBranchesSpec());
        final List<String> include = DescriptorImpl.splitBranchSpec(this.getIncludeBranchesSpec());
        if (exclude.isEmpty() && include.isEmpty()) {
            log.debug("No exclude/include filters - branch {} is allowed.", branchName);
            return true;
        }

        final AntPathMatcher matcher = new AntPathMatcher();
        for (final String pattern : exclude) {
            if (matcher.match(pattern, branchName)) {
                log.debug("Branch {} is excluded because it matches exclude filter {}", branchName, pattern);
                return false;
            }
        }
        for (final String pattern : include) {
            if (matcher.match(pattern, branchName)) {
                log.debug("Branch {} is included because it matches include filter {}", branchName, pattern);
                return true;
            }
        }

        log.debug("Branch {} is excluded because it does not match any include filter", branchName);
        return false;
    }

    public void run(final GitlabPushHook event) {
        final String branchName = StringUtils.removeStart(event.getRef(), "refs/heads/");
        if (this.isTriggerOnPush() && this.isBranchAllowed(branchName)) {
            final List<ParameterValue> parameters = new ArrayList<ParameterValue>();
            parameters.add(new StringParameterValue("gitlabSourceBranch", branchName));
            parameters.add(new StringParameterValue("gitlabTargetBranch", branchName));

            parameters.add(BuildParameters.GITLAB_SOURCE_BRANCH.withValueOf(branchName));
            parameters.add(BuildParameters.GITLAB_TARGET_BRANCH.withValueOf(branchName));

            final GitLabPushCause cause = new GitLabPushCause(event);
            this.schedule(cause, new ParametersAction(parameters), new RevisionParameterAction(event.getAfter()));
        }
    }

    public void run(final GitlabMergeRequestHook event) {
        final GitlabMergeRequestHookAttrs mr = event.getObjectAttributes();
        log.debug("Merge state: {}, status: {}", mr.getState(), mr.getMergeStatus());

        final String branchName = mr.getSourceBranch();
        if (this.isTriggerOnMergeRequest() && this.isBranchAllowed(branchName)) {
            if (mr.getState() != State.OPENED && mr.getState() != State.REOPENED) {
                log.info("Skipping merge request #{} because it's not open.", mr.getIid());
                return;
            }
            if (mr.getMergeStatus() == MergeStatus.CANNOT_BE_MERGED) {
                log.info("Skipping merge request #{} because it cannot be merged.", mr.getIid());
                return;
            }

            final List<ParameterValue> parameters = new ArrayList<ParameterValue>();
            parameters.add(new StringParameterValue("gitlabSourceBranch", branchName));
            parameters.add(new StringParameterValue("gitlabTargetBranch", mr.getTargetBranch()));

            parameters.add(BuildParameters.GITLAB_SOURCE_BRANCH.withValueOf(branchName));
            parameters.add(BuildParameters.GITLAB_TARGET_BRANCH.withValueOf(mr.getTargetBranch()));
            parameters.add(BuildParameters.GITLAB_SOURCE_SSH.withValueOf(mr.getSource().getSshUrl()));
            parameters.add(BuildParameters.GITLAB_SOURCE_HTTP.withValueOf(mr.getSource().getHttpUrl()));

            final GitLabMergeCause cause = new GitLabMergeCause(event);
            this.schedule(cause, new ParametersAction(parameters));
        }
    }

    public void run(final GitlabMergeRequest mr) {
        final String branchName = mr.getSourceBranch();
        if (this.isTriggerOnMergeRequest() && this.isBranchAllowed(branchName)) {
            final List<ParameterValue> parameters = new ArrayList<ParameterValue>();
            parameters.add(new StringParameterValue("gitlabSourceBranch", branchName));
            parameters.add(new StringParameterValue("gitlabTargetBranch", mr.getTargetBranch()));

            parameters.add(BuildParameters.GITLAB_SOURCE_BRANCH.withValueOf(branchName));
            parameters.add(BuildParameters.GITLAB_TARGET_BRANCH.withValueOf(mr.getTargetBranch()));

            final GitlabAPI api = this.getDescriptor().newGitlabConnection();
            if (api != null) {
                try {
                    final GitlabProject source = api.getProject(mr.getSourceProjectId());
                    if (source != null) {
                        parameters.add(BuildParameters.GITLAB_SOURCE_SSH.withValueOf(source.getSshUrl()));
                        parameters.add(BuildParameters.GITLAB_SOURCE_HTTP.withValueOf(source.getHttpUrl()));

                        final GitLabMergeCause cause = new GitLabMergeCause(mr);
                        this.schedule(cause, new ParametersAction(parameters));
                    } else {
                        log.warn("Cannot find source project #{} (insufficient permissions?)", mr.getSourceProjectId());
                    }
                } catch (final IOException ex) {
                    log.warn("Cannot fetch source project #{}", mr.getSourceProjectId(), ex);
                }
            }
        }
    }

    private void schedule(final Cause cause, final Action... actions) {
        if (job instanceof SCMTriggerItem) {
            final SCMTriggerItem i = (SCMTriggerItem) job;
            final Action[] realActions = new Action[actions.length + 1];
            realActions[0] = new CauseAction(cause);
            System.arraycopy(actions, 0, realActions, 1, actions.length);
            i.scheduleBuild2(i.getQuietPeriod(), realActions);
        } else if (job instanceof AbstractProject<?, ?>) {
            final AbstractProject<?, ?> i = (AbstractProject<?, ?>) job;
            i.scheduleBuild2(i.getQuietPeriod(), cause, actions);
        } else {
            log.warn("Cannot pass actions to job {}.", job);
            job.scheduleBuild(cause);
        }
    }

//    public void onPost(final GitLabPushRequest req) {
//        if (this.isTriggerOnPush() && this.isBranchAllowed(this.getSourceBranch(req))) {
//            getDescriptor().queue.execute(new Runnable() {
//
//                public void run() {
//                    LOGGER.log(Level.INFO, "{0} triggered.", job.getName());
//                    final AbstractProject<?, ?> p = (AbstractProject<?, ?>) job;
//                    String name = " #" + p.getNextBuildNumber();
//                    GitLabPushCause cause = null;
//                    Action[] actions = createActions(req);
//                    if (p.scheduleBuild(p.getQuietPeriod(), cause, actions)) {
//                        LOGGER.log(Level.INFO, "GitLab Push Request detected in {0}. Triggering {1}", new String[] { job.getName(), name });
//                    } else {
//                        LOGGER.log(Level.INFO, "GitLab Push Request detected in {0}. Job is already in the queue.", job.getName());
//                    }
//                }
//
//                private Action[] createActions(GitLabPushRequest req) {
//                    ArrayList<Action> actions = new ArrayList<Action>();
//
//                    String branch = getSourceBranch(req);
//
//                    LOGGER.log(Level.INFO, "GitLab Push Request from branch {0}.", branch);
//
//                    Map<String, ParameterValue> values = new HashMap<String, ParameterValue>();
//                    values.put("gitlabSourceBranch", new StringParameterValue("gitlabSourceBranch", branch));
//                    values.put("gitlabTargetBranch", new StringParameterValue("gitlabTargetBranch", branch));
//                    values.put("gitlabBranch", new StringParameterValue("gitlabBranch", branch));
//                    values.put("gitlabSourceRepoName", new StringParameterValue("gitlabSourceRepoName", getSourceRepoNameDefault()));
//                    values.put("gitlabSourceRepoURL", new StringParameterValue("gitlabSourceRepoURL", getSourceRepoURLDefault().toString()));
//
//                    List<ParameterValue> listValues = new ArrayList<ParameterValue>(values.values());
//
//                    ParametersAction parametersAction = new ParametersAction(listValues);
//                    actions.add(parametersAction);
//
//                    RevisionParameterAction revision = new RevisionParameterAction(req.getLastCommit().getId());
//                    actions.add(revision);
//                    Action[] actionsArray = actions.toArray(new Action[0]);
//
//                    return actionsArray;
//                }
//
//            });
//        }
//    }
//
//    public void onPost(final GitLabMergeRequest req) {
//        if (this.isTriggerOnMergeRequest()) {
//            getDescriptor().queue.execute(new Runnable() {
//
//                public void run() {
//                    LOGGER.log(Level.INFO, "{0} triggered.", job.getName());
//                    final AbstractProject<?, ?> p = (AbstractProject<?, ?>) job;
//                    String name = " #" + p.getNextBuildNumber();
//                    GitLabMergeCause cause = null;
//                    Action[] actions = createActions(req);
//                    if (p.scheduleBuild(p.getQuietPeriod(), cause, actions)) {
//                        LOGGER.log(Level.INFO, "GitLab Merge Request detected in {0}. Triggering {1}", new String[] { job.getName(), name });
//                    } else {
//                        LOGGER.log(Level.INFO, "GitLab Merge Request detected in {0}. Job is already in the queue.", job.getName());
//                    }
//                }
//
//                private Action[] createActions(GitLabMergeRequest req) {
//                    List<Action> actions = new ArrayList<Action>();
//
//                    Map<String, ParameterValue> values = new HashMap<String, ParameterValue>();
//                    values.put("gitlabSourceBranch", new StringParameterValue("gitlabSourceBranch", getSourceBranch(req)));
//                    values.put("gitlabTargetBranch", new StringParameterValue("gitlabTargetBranch", req.getObjectAttribute().getTargetBranch()));
//
//                    String sourceRepoName = getSourceRepoNameDefault();
//                    String sourceRepoURL = getSourceRepoURLDefault().toString();
//
//                    if (!getDescriptor().getGitlabHostUrl().isEmpty()) {
//                        // Get source repository if communication to Gitlab is possible
//                        try {
//                            sourceRepoName = req.getSourceProject(getDesc().getGitlab()).getPathWithNamespace();
//                            sourceRepoURL = req.getSourceProject(getDesc().getGitlab()).getSshUrl();
//                        } catch (IOException ex) {
//                            LOGGER.log(Level.WARNING, "Could not fetch source project''s data from Gitlab. '('{0}':' {1}')'", new String[] { ex.toString(), ex.getMessage() });
//                        }
//                    }
//
//                    values.put("gitlabSourceRepoName", new StringParameterValue("gitlabSourceRepoName", sourceRepoName));
//                    values.put("gitlabSourceRepoURL", new StringParameterValue("gitlabSourceRepoURL", sourceRepoURL));
//
//                    List<ParameterValue> listValues = new ArrayList<ParameterValue>(values.values());
//
//                    ParametersAction parametersAction = new ParametersAction(listValues);
//                    actions.add(parametersAction);
//
//                    Action[] actionsArray = actions.toArray(new Action[0]);
//
//                    return actionsArray;
//                }
//
//            });
//        }
//    }
    /**
     * Get the URL of the first declared repository in the project configuration.
     * Use this as default source repository url.
     *
     * @return URIish the default value of the source repository url
     * @throws IllegalStateException Project does not use git scm.
     */
    @Nullable
    protected static URIish getSourceRepoURLDefault(final Job<?, ?> job) throws IllegalStateException {
        SCM scm = ((AbstractProject<?, ?>) job).getScm();
        if (!(scm instanceof GitSCM)) {
            throw new IllegalStateException("This repo does not use git.");
        }

        List<RemoteConfig> repositories = ((GitSCM) scm).getRepositories();
        if (!repositories.isEmpty()) {
            RemoteConfig defaultRepository = repositories.get(repositories.size() - 1);
            List<URIish> uris = defaultRepository.getURIs();
            if (!uris.isEmpty()) {
                return uris.get(uris.size() - 1);
            }
        }

        return null;
    }

    protected URIish getSourceRepoURLDefault() throws IllegalStateException {
        return getSourceRepoURLDefault((Job<?, ?>) this.job);
    }

    /**
     * Get the Name of the first declared repository in the project configuration.
     * Use this as default source repository Name.
     *
     * @return String with the default name of the source repository
     */
    protected String getSourceRepoNameDefault() {
        String result = null;
        SCM scm = ((AbstractProject<?, ?>) job).getScm();
        if (!(scm instanceof GitSCM)) {
            throw new IllegalArgumentException("This repo does not use git.");
        }
        if (scm instanceof GitSCM) {
            List<RemoteConfig> repositories = ((GitSCM) scm).getRepositories();
            if (!repositories.isEmpty()) {
                return repositories.get(repositories.size() - 1).getName();
            }
        }
        return null;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    public static final class ConverterImpl extends XStream2.PassthruConverter<GitLabTrigger> {

        public ConverterImpl(final XStream2 xstream) {
            super(xstream);

            xstream.registerLocalConverter(GitLabTrigger.class, "includeBranchesSpec", new Converter() {

                @Override
                public Object unmarshal(final HierarchicalStreamReader reader, final UnmarshallingContext context) {
                    if ("includeBranchesSpec".equalsIgnoreCase(reader.getNodeName())) {
                        return reader.getValue();
                    }
                    if ("allowedBranchesSpec".equalsIgnoreCase(reader.getNodeName())) {
                        return reader.getValue();
                    }
                    if ("allowedBranches".equalsIgnoreCase(reader.getNodeName())) {
                        final Converter iconv = new CollectionConverter(xstream.getMapper(), List.class);
                        final List<?> list = (List<?>) iconv.unmarshal(reader, context);
                        return Joiner.on(',').join(list);
                    }

                    throw new AbstractReflectionConverter.UnknownFieldException(context.getRequiredType().getName(), reader.getNodeName());
                }

                @Override
                public void marshal(final Object source, final HierarchicalStreamWriter writer, final MarshallingContext context) {
                    writer.setValue(String.valueOf(source));
                }

                @Override @SuppressWarnings("rawtypes")
                public boolean canConvert(final Class type) {
                    return List.class.isAssignableFrom(type) || String.class.isAssignableFrom(type);
                }

            });

            synchronized (xstream) {
                xstream.setMapper(new MapperWrapper(xstream.getMapperInjectionPoint()) {

                    @Override @SuppressWarnings("rawtypes")
                    public String realMember(final Class type, final String serialized) {
                        if (GitLabTrigger.class.equals(type)) {
                            if ("allowedBranchesSpec".equalsIgnoreCase(serialized) || "allowedBranches".equalsIgnoreCase(serialized)) {
                                return "includeBranchesSpec";
                            }
                        }
                        return super.realMember(type, serialized);
                    }

                });
            }
        }

        @Override
        protected void callback(final GitLabTrigger obj, final UnmarshallingContext context) {
            /* no-op */
        }

    }

    @Extension @Getter @Setter
    public static class DescriptorImpl extends TriggerDescriptor {

        private String gitlabApiToken;
        private String gitlabHostUrl;
        private boolean ignoreCertificateErrors;

        public DescriptorImpl() {
            this.loadOldConfigFile();
            this.load();
        }

        private void loadOldConfigFile() {
            final XmlFile ncf = this.getConfigFile();
            final XmlFile[] oca = this.getOldConfigFile();

            for (int i = 0, max = oca.length; i < max; ++i) {
                final XmlFile oc = oca[i];
                final XmlFile nc = (i + 1) < max ? oca[i + 1] : ncf;
                if (!nc.exists()) {
                    if (oc.exists()) {
                        if (!oc.getFile().renameTo(nc.getFile())) {
                            log.warn("Cannot move old configuration from %s to %s", oc, nc);
                        }
                    }
                } else {
                    if (oc.exists()) {
                        log.info("Removing stale configuration file %s", oc);
                        oc.delete();
                    }
                }

                final XStream2 xs = (XStream2) ncf.getXStream();
                xs.addCompatibilityAlias(Util.changeExtension(oc.getFile(), "$DescriptorImpl").getName(), DescriptorImpl.class);
            }
        }

        private XmlFile[] getOldConfigFile() {
            final File rd = Jenkins.getActiveInstance().getRootDir();
            return new XmlFile[] {
                new XmlFile(new File(rd, "com.dabsquared.gitlabjenkins.GitLabPushTrigger.xml"))
            };
        }

        @Override
        public boolean isApplicable(final Item item) {
            return SCMTriggerItem.SCMTriggerItems.asSCMTriggerItem(item) != null;
        }

        @Override
        public String getDisplayName() {
            return "Build when a change is pushed to GitLab";
        }

        public String getHookUrl(@AncestorInPath final Job<?, ?> job) {
            return Util.ensureEndsWith(Jenkins.getActiveInstance().getRootUrl(), "/")
                   + Util.ensureEndsWith(GitLabRootAction.URL_NAME, "/")
                   + Util.removeTrailingSlash(job.getUrl());
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            req.bindJSON(this, formData);
            save();
            return super.configure(req, formData);
        }

        private List<String> getProjectBranches(@Nonnull final Job<?, ?> job) throws IOException, IllegalStateException {
            final URIish sourceRepository = getSourceRepoURLDefault(job);
            if (sourceRepository == null) {
                throw new IllegalStateException(Messages.GitLabPushTrigger_NoSourceRepository());
            }

            try {
                final List<String> branchNames = new ArrayList<String>();
                final GitlabAPI api = newGitlabConnection();
                if (api != null) {
                    /* TODO until java-gitlab-api v1.1.5 is released,
                     * cannot search projects by namespace/name
                     * For now getting project id before getting project branches */
                    final List<GitlabProject> projects = api.getProjects();
                    for (final GitlabProject gitlabProject : projects) {
                        if (gitlabProject.getSshUrl().equalsIgnoreCase(sourceRepository.toString())
                            || gitlabProject.getHttpUrl().equalsIgnoreCase(sourceRepository.toString())) {
                            //Get all branches of project
                            final List<GitlabBranch> branches = api.getBranches(gitlabProject);
                            for (final GitlabBranch branch : branches) {
                                branchNames.add(branch.getName());
                            }
                            break;
                        }
                    }
                }

                return branchNames;
            } catch (final Error error) {
                /* WTF WTF WTF */
                final Throwable cause = error.getCause();
                if (cause instanceof IOException) {
                    throw (IOException) cause;
                } else {
                    throw error;
                }
            }
        }

        private static List<String> splitBranchSpec(final String spec) {
            return Lists.newArrayList(Splitter.on(',').omitEmptyStrings().trimResults().split(spec));
        }

        private AutoCompletionCandidates doAutoCompleteBranchesSpec(@Nonnull final Job<?, ?> job) {
            final AutoCompletionCandidates ac = new AutoCompletionCandidates();
            try {
                ac.getValues().addAll(this.getProjectBranches(job));
            } catch (final IllegalStateException ex) {
                /* no-op */
            } catch (final IOException ex) {
                /* no-op */
            }

            return ac;
        }

        public AutoCompletionCandidates doAutoCompleteIncludeBranchesSpec(@AncestorInPath final Job<?, ?> job) {
            return this.doAutoCompleteBranchesSpec(job);
        }

        public AutoCompletionCandidates doAutoCompleteExcludeBranchesSpec(@AncestorInPath final Job<?, ?> job) {
            return this.doAutoCompleteBranchesSpec(job);
        }

        private FormValidation doCheckBranchesSpec(@AncestorInPath final Job<?, ?> project, @QueryParameter final String value) {
            if (!project.hasPermission(Item.CONFIGURE)) {
                return FormValidation.ok();
            }

            final List<String> branchSpecs = splitBranchSpec(value);
            if (branchSpecs.isEmpty()) {
                return FormValidation.ok();
            }

            final List<String> projectBranches;
            try {
                projectBranches = this.getProjectBranches(project);
            } catch (final IllegalStateException ex) {
                return FormValidation.warning(Messages.GitLabPushTrigger_CannotConnectToGitLab(ex.getMessage()));
            } catch (final IOException ex) {
                return FormValidation.warning(project.hasPermission(Jenkins.ADMINISTER) ? ex : null,
                                              Messages.GitLabPushTrigger_CannotCheckBranches());
            }

            final Multimap<String, String> matchedSpecs = HashMultimap.create();
            final AntPathMatcher matcher = new AntPathMatcher();
            for (final String projectBranch : projectBranches) {
                for (final String branchSpec : branchSpecs) {
                    if (matcher.match(branchSpec, projectBranch)) {
                        matchedSpecs.put(branchSpec, projectBranch);
                    }
                }
            }

            branchSpecs.removeAll(matchedSpecs.keySet());
            if (!branchSpecs.isEmpty()) {
                final String unknownBranchNames = StringUtils.join(branchSpecs, ", ");
                return FormValidation.warning(Messages.GitLabPushTrigger_BranchesNotFound(unknownBranchNames));
            } else {
                final int matchedBranchesCount = Sets.newHashSet(matchedSpecs.values()).size();
                return FormValidation.ok(Messages.GitLabPushTrigger_BranchesMatched(matchedBranchesCount));
            }
        }

        public FormValidation doCheckIncludeBranchesSpec(@AncestorInPath final Job<?, ?> project, @QueryParameter final String value) {
            return this.doCheckBranchesSpec(project, value);
        }

        public FormValidation doCheckExcludeBranchesSpec(@AncestorInPath final Job<?, ?> project, @QueryParameter final String value) {
            return this.doCheckBranchesSpec(project, value);
        }

        public FormValidation doCheckToken(@QueryParameter final String value) {
            return FormValidation.validateRequired(value);
        }

        public FormValidation doTestConnection(@QueryParameter("gitlabHostUrl") String hostUrl,
                                               @QueryParameter("gitlabApiToken") String token,
                                               @QueryParameter("ignoreCertificateErrors") final boolean ignoreCertificateErrors) throws IOException {
            final List<FormValidation> errors = new ArrayList<FormValidation>();
            if ((hostUrl = Util.fixEmptyAndTrim(hostUrl)) == null) {
                errors.add(FormValidation.error("Host url is required."));
            }
            if ((token = Util.fixEmptyAndTrim(token)) == null) {
                errors.add(FormValidation.error("API token is required."));
            }
            if (hostUrl != null && token != null) {
                try {
                    this.newGitLabConnection(hostUrl, token, ignoreCertificateErrors);
                    errors.add(FormValidation.ok("Connection successful."));
                } catch (final IOException ex) {
                    errors.add(FormValidation.error("Connection error: %s", ex.getMessage()));
                }
            }

            return FormValidation.aggregate(errors);
        }

        @Nullable
        public GitlabAPI newGitlabConnection() {
            if (this.getGitlabHostUrl() != null && this.getGitlabApiToken() != null) {
                try {
                    return this.newGitLabConnection(this.getGitlabHostUrl(), this.getGitlabApiToken(), this.isIgnoreCertificateErrors());
                } catch (final IOException ex) {
                    log.warn("Gitlab API access not available.", ex);
                    return null;
                }
            } else {
                log.debug("Gitlab host url and/or api token not supplied, cannot create api connection.");
                return null;
            }
        }

        @Nonnull
        private GitlabAPI newGitLabConnection(final String url, final String token, final boolean ic) throws IOException {
            final GitlabAPI api = GitlabAPI.connect(url, token);
            api.ignoreCertificateErrors(ic);
            api.getCurrentSession();

            return api;
        }

    }

}
