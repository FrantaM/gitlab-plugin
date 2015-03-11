package com.dabsquared.gitlabjenkins;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nullable;

import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.gitlab.api.models.GitlabBranch;
import org.gitlab.api.models.GitlabProject;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.springframework.util.AntPathMatcher;

import com.dabsquared.gitlabjenkins.models.attrs.GitlabMergeRequestHookAttrs;
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

import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractBuild;
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
import hudson.model.Result;
import hudson.model.StringParameterValue;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.RevisionParameterAction;
import hudson.scm.SCM;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.FormValidation;
import hudson.util.SequentialExecutionQueue;
import hudson.util.XStream2;

import jenkins.model.Jenkins;
import jenkins.triggers.SCMTriggerItem;

/**
 * Triggers a build when we receive a GitLab WebHook.
 *
 * @author Daniel Brooks
 */
public class GitLabPushTrigger extends Trigger<BuildableItem> {

    private static final Logger LOGGER = Logger.getLogger(GitLabPushTrigger.class.getName());
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
    private boolean setBuildDescription = true;
    private boolean addNoteOnMergeRequest = true;
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
    public GitLabPushTrigger(boolean setBuildDescription) {
        this.setBuildDescription = setBuildDescription;
    }

    public boolean getSetBuildDescription() {
        return setBuildDescription;
    }

    public boolean getAddNoteOnMergeRequest() {
        return addNoteOnMergeRequest;
    }

    private boolean isBranchAllowed(final String branchName) {
        final List<String> exclude = DescriptorImpl.splitBranchSpec(this.getExcludeBranchesSpec());
        final List<String> include = DescriptorImpl.splitBranchSpec(this.getIncludeBranchesSpec());
        if (exclude.isEmpty() && include.isEmpty()) {
            return true;
        }

        final AntPathMatcher matcher = new AntPathMatcher();
        for (final String pattern : exclude) {
            if (matcher.match(pattern, branchName)) {
                return false;
            }
        }
        for (final String pattern : include) {
            if (matcher.match(pattern, branchName)) {
                return true;
            }
        }

        return false;
    }

    public void run(final GitlabPushHook event) {
        final String branchName = StringUtils.removeStart(event.getRef(), "refs/heads/");
        if (this.isTriggerOnPush() && this.isBranchAllowed(branchName)) {
            final List<ParameterValue> parameters = new ArrayList<ParameterValue>();
            parameters.add(new StringParameterValue("gitlabSourceBranch", branchName));
            parameters.add(new StringParameterValue("gitlabTargetBranch", branchName));

            final GitLabPushCause cause = new GitLabPushCause(event.getUserName());
            this.schedule(cause, new ParametersAction(parameters), new RevisionParameterAction(event.getAfter()));
        }
    }

    public void run(final GitlabMergeRequestHook event) {
        final GitlabMergeRequestHookAttrs mr = event.getObjectAttributes();
        final String branchName = mr.getSourceBranch();
        if (this.isTriggerOnMergeRequest() && this.isBranchAllowed(branchName)) {
            final List<ParameterValue> parameters = new ArrayList<ParameterValue>();
            parameters.add(new StringParameterValue("gitlabSourceBranch", branchName));
            parameters.add(new StringParameterValue("gitlabTargetBranch", mr.getTargetBranch()));

            final GitLabMergeCause cause = new GitLabMergeCause(event);
            this.schedule(cause, new ParametersAction(parameters), new RevisionParameterAction(mr.getLastCommit().getId()));
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
            LOGGER.severe(String.format("Cannot pass actions to job %s.", job));
            job.scheduleBuild(cause);
        }
    }

    public void onPost(final GitLabPushRequest req) {
        if (this.isTriggerOnPush() && this.isBranchAllowed(this.getSourceBranch(req))) {
            getDescriptor().queue.execute(new Runnable() {

                public void run() {
                    LOGGER.log(Level.INFO, "{0} triggered.", job.getName());
                    final AbstractProject<?,?> p = (AbstractProject<?,?>)job;
                    String name = " #" + p.getNextBuildNumber();
                    GitLabPushCause cause = createGitLabPushCause(req);
                    Action[] actions = createActions(req);
                    if (p.scheduleBuild(p.getQuietPeriod(), cause, actions)) {
                        LOGGER.log(Level.INFO, "GitLab Push Request detected in {0}. Triggering {1}", new String[] { job.getName(), name });
                    } else {
                        LOGGER.log(Level.INFO, "GitLab Push Request detected in {0}. Job is already in the queue.", job.getName());
                    }
                }

                private GitLabPushCause createGitLabPushCause(GitLabPushRequest req) {
                    GitLabPushCause cause;
                    String triggeredByUser = req.getCommits().get(0).getAuthor().getName();
                    try {
                        cause = new GitLabPushCause(triggeredByUser, getLogFile());
                    } catch (IOException ex) {
                        cause = new GitLabPushCause(triggeredByUser);
                    }
                    return cause;
                }

                private Action[] createActions(GitLabPushRequest req) {
                    ArrayList<Action> actions = new ArrayList<Action>();

                    String branch = getSourceBranch(req);

                    LOGGER.log(Level.INFO, "GitLab Push Request from branch {0}.", branch);

                    Map<String, ParameterValue> values = new HashMap<String, ParameterValue>();
                    values.put("gitlabSourceBranch", new StringParameterValue("gitlabSourceBranch", branch));
                    values.put("gitlabTargetBranch", new StringParameterValue("gitlabTargetBranch", branch));
                    values.put("gitlabBranch", new StringParameterValue("gitlabBranch", branch));
                    values.put("gitlabSourceRepoName", new StringParameterValue("gitlabSourceRepoName", getDesc().getSourceRepoNameDefault()));
                    values.put("gitlabSourceRepoURL", new StringParameterValue("gitlabSourceRepoURL", getDesc().getSourceRepoURLDefault().toString()));

                    List<ParameterValue> listValues = new ArrayList<ParameterValue>(values.values());

                    ParametersAction parametersAction = new ParametersAction(listValues);
                    actions.add(parametersAction);

                    RevisionParameterAction revision = new RevisionParameterAction(req.getLastCommit().getId());
                    actions.add(revision);
                    Action[] actionsArray = actions.toArray(new Action[0]);

                    return actionsArray;
                }

            });
        }
    }

    public void onPost(final GitLabMergeRequest req) {
        if (this.isTriggerOnMergeRequest()) {
            getDescriptor().queue.execute(new Runnable() {

                public void run() {
                    LOGGER.log(Level.INFO, "{0} triggered.", job.getName());
                    final AbstractProject<?,?> p = (AbstractProject<?,?>)job;
                    String name = " #" + p.getNextBuildNumber();
                    GitLabMergeCause cause = null;
                    Action[] actions = createActions(req);
                    if (p.scheduleBuild(p.getQuietPeriod(), cause, actions)) {
                        LOGGER.log(Level.INFO, "GitLab Merge Request detected in {0}. Triggering {1}", new String[] { job.getName(), name });
                    } else {
                        LOGGER.log(Level.INFO, "GitLab Merge Request detected in {0}. Job is already in the queue.", job.getName());
                    }
                }

                private Action[] createActions(GitLabMergeRequest req) {
                    List<Action> actions = new ArrayList<Action>();

                    Map<String, ParameterValue> values = new HashMap<String, ParameterValue>();
                    values.put("gitlabSourceBranch", new StringParameterValue("gitlabSourceBranch", getSourceBranch(req)));
                    values.put("gitlabTargetBranch", new StringParameterValue("gitlabTargetBranch", req.getObjectAttribute().getTargetBranch()));

                    String sourceRepoName = getDesc().getSourceRepoNameDefault();
                    String sourceRepoURL = getDesc().getSourceRepoURLDefault().toString();

                    if (!getDescriptor().getGitlabHostUrl().isEmpty()) {
                        // Get source repository if communication to Gitlab is possible
                        try {
                            sourceRepoName = req.getSourceProject(getDesc().getGitlab()).getPathWithNamespace();
                            sourceRepoURL = req.getSourceProject(getDesc().getGitlab()).getSshUrl();
                        } catch (IOException ex) {
                            LOGGER.log(Level.WARNING, "Could not fetch source project''s data from Gitlab. '('{0}':' {1}')'", new String[] { ex.toString(), ex.getMessage() });
                        }
                    }

                    values.put("gitlabSourceRepoName", new StringParameterValue("gitlabSourceRepoName", sourceRepoName));
                    values.put("gitlabSourceRepoURL", new StringParameterValue("gitlabSourceRepoURL", sourceRepoURL));

                    List<ParameterValue> listValues = new ArrayList<ParameterValue>(values.values());

                    ParametersAction parametersAction = new ParametersAction(listValues);
                    actions.add(parametersAction);

                    Action[] actionsArray = actions.toArray(new Action[0]);

                    return actionsArray;
                }

            });
        }
    }

    private void setBuildCauseInJob(AbstractBuild abstractBuild) {
        if (setBuildDescription) {
            Cause pcause = abstractBuild.getCause(GitLabPushCause.class);
            Cause mcause = abstractBuild.getCause(GitLabMergeCause.class);
            String desc = null;
            if (pcause != null) {
                desc = pcause.getShortDescription();
            }
            if (mcause != null) {
                desc = mcause.getShortDescription();
            }
            if (desc != null && desc.length() > 0) {
                try {
                    abstractBuild.setDescription(desc);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public void onCompleted(AbstractBuild build) {
        Cause mCause = build.getCause(GitLabMergeCause.class);
        if (mCause != null && mCause instanceof GitLabMergeCause) {
            onCompleteMergeRequest(build, (GitLabMergeCause) mCause);
        }

    }

    private void onCompleteMergeRequest(AbstractBuild abstractBuild, GitLabMergeCause cause) {
        if (addNoteOnMergeRequest) {
            StringBuilder msg = new StringBuilder();
            if (abstractBuild.getResult() == Result.SUCCESS) {
                msg.append(":white_check_mark:");
            } else {
                msg.append(":anguished:");
            }
            msg.append(" Jenkins Build ").append(abstractBuild.getResult().color.getDescription());
            String buildUrl = Jenkins.getInstance().getRootUrl() + abstractBuild.getUrl();
            msg.append("\n\nResults available at: ")
                    .append("[").append("Jenkins").append("](").append(buildUrl).append(")");
            try {
                GitlabProject proj = new GitlabProject();
                proj.setId(cause.getMergeRequest().getObjectAttributes().getTargetProjectId());
                org.gitlab.api.models.GitlabMergeRequest mr = this.getDescriptor().getGitlab().instance().
                        getMergeRequest(proj, cause.getMergeRequest().getObjectAttributes().getId());
                this.getDescriptor().getGitlab().instance().createNote(mr, msg.toString());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }

    public void onStarted(AbstractBuild abstractBuild) {
        setBuildCauseInJob(abstractBuild);
    }

    private String getSourceBranch(GitLabRequest req) {
        String result = null;
        if (req instanceof GitLabPushRequest) {
            result = ((GitLabPushRequest) req).getRef().replaceAll("refs/heads/", "");
        } else {
            result = ((GitLabMergeRequest) req).getObjectAttribute().getSourceBranch();
        }

        return result;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return DescriptorImpl.get();
    }

    public static DescriptorImpl getDesc() {
        return DescriptorImpl.get();
    }

    public File getLogFile() {
        return new File(job.getRootDir(), "gitlab-polling.log");
    }

    public static final class ConverterImpl extends XStream2.PassthruConverter<GitLabPushTrigger> {

        public ConverterImpl(final XStream2 xstream) {
            super(xstream);

            xstream.registerLocalConverter(GitLabPushTrigger.class, "includeBranchesSpec", new Converter() {

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

                public void marshal(final Object source, final HierarchicalStreamWriter writer, final MarshallingContext context) {
                    writer.setValue(String.valueOf(source));
                }

                public boolean canConvert(final Class type) {
                    return List.class.isAssignableFrom(type) || String.class.isAssignableFrom(type);
                }

            });

            synchronized (xstream) {
                xstream.setMapper(new MapperWrapper(xstream.getMapperInjectionPoint()) {

                    @Override
                    public String realMember(final Class type, final String serialized) {
                        if (GitLabPushTrigger.class.equals(type)) {
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
        protected void callback(final GitLabPushTrigger obj, final UnmarshallingContext context) {
            /* no-op */
        }

    }

    @Extension
    public static class DescriptorImpl extends TriggerDescriptor {

        AbstractProject project;
        private String gitlabApiToken;
        private String gitlabHostUrl = "";
        private boolean ignoreCertificateErrors = false;
        private transient final SequentialExecutionQueue queue = new SequentialExecutionQueue(Jenkins.MasterComputer.threadPoolForRemoting);
        private transient GitLab gitlab;

        public DescriptorImpl() {
            load();
        }

        @Override
        public boolean isApplicable(Item item) {
            if (item instanceof AbstractProject) {
                project = (AbstractProject) item;
                return true;
            } else {
                return false;
            }
        }

        @Override
        public String getDisplayName() {
            if (project == null) {
                return "Build when a change is pushed to GitLab, unknown URL";
            }

            final List<String> projectParentsUrl = new ArrayList<String>();

            try {
                for (Object parent = project.getParent(); parent instanceof Item; parent = ((Item) parent)
                     .getParent()) {
                    projectParentsUrl.add(0, ((Item) parent).getName());
                }
            } catch (IllegalStateException e) {
                return "Build when a change is pushed to GitLab, unknown URL";
            }
            final StringBuilder projectUrl = new StringBuilder();
            projectUrl.append(Jenkins.getInstance().getRootUrl());
            projectUrl.append(GitLabWebHook.WEBHOOK_URL);
            projectUrl.append('/');
            for (final String parentUrl : projectParentsUrl) {
                projectUrl.append(Util.rawEncode(parentUrl));
                projectUrl.append('/');
            }
            projectUrl.append(Util.rawEncode(project.getName()));

            return "Build when a change is pushed to GitLab. GitLab CI Service URL: " + projectUrl;
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            gitlabApiToken = formData.getString("gitlabApiToken");
            gitlabHostUrl = formData.getString("gitlabHostUrl");
            ignoreCertificateErrors = formData.getBoolean("ignoreCertificateErrors");
            save();
            gitlab = new GitLab();
            return super.configure(req, formData);
        }

        private List<String> getProjectBranches() throws IOException, IllegalStateException {
            final URIish sourceRepository = getSourceRepoURLDefault();
            if (sourceRepository == null) {
                throw new IllegalStateException(Messages.GitLabPushTrigger_NoSourceRepository());
            }

            try {
                final List<String> branchNames = new ArrayList<String>();
                if (!gitlabHostUrl.isEmpty()) {
                    /* TODO until java-gitlab-api v1.1.5 is released,
                     * cannot search projects by namespace/name
                     * For now getting project id before getting project branches */
                    final List<GitlabProject> projects = getGitlab().instance().getProjects();
                    for (final GitlabProject gitlabProject : projects) {
                        if (gitlabProject.getSshUrl().equalsIgnoreCase(sourceRepository.toString())
                            || gitlabProject.getHttpUrl().equalsIgnoreCase(sourceRepository.toString())) {
                            //Get all branches of project
                            final List<GitlabBranch> branches = getGitlab().instance().getBranches(gitlabProject);
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

        private AutoCompletionCandidates doAutoCompleteBranchesSpec() {
            final AutoCompletionCandidates ac = new AutoCompletionCandidates();
            try {
                ac.getValues().addAll(this.getProjectBranches());
            } catch (final IllegalStateException ex) {
                /* no-op */
            } catch (final IOException ex) {
                /* no-op */
            }

            return ac;
        }

        public AutoCompletionCandidates doAutoCompleteIncludeBranchesSpec() {
            return this.doAutoCompleteBranchesSpec();
        }

        public AutoCompletionCandidates doAutoCompleteExcludeBranchesSpec() {
            return this.doAutoCompleteBranchesSpec();
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
                projectBranches = this.getProjectBranches();
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

        /**
         * Get the URL of the first declared repository in the project configuration.
         * Use this as default source repository url.
         *
         * @return URIish the default value of the source repository url
         * @throws IllegalStateException Project does not use git scm.
         */
        @Nullable
        protected URIish getSourceRepoURLDefault() throws IllegalStateException {
            SCM scm = project.getScm();
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

        /**
         * Get the Name of the first declared repository in the project configuration.
         * Use this as default source repository Name.
         *
         * @return String with the default name of the source repository
         */
        protected String getSourceRepoNameDefault() {
            String result = null;
            SCM scm = project.getScm();
            if (!(scm instanceof GitSCM)) {
                throw new IllegalArgumentException("This repo does not use git.");
            }
            if (scm instanceof GitSCM) {
                List<RemoteConfig> repositories = ((GitSCM) scm).getRepositories();
                if (!repositories.isEmpty()) {
                    result = repositories.get(repositories.size() - 1).getName();
                }
            }
            return result;
        }

        public FormValidation doCheckGitlabHostUrl(@QueryParameter String value) {
            if (value == null || value.isEmpty()) {
                return FormValidation.error("Gitlab host URL required.");
            }

            return FormValidation.ok();
        }

        public FormValidation doCheckGitlabApiToken(@QueryParameter String value) {
            if (value == null || value.isEmpty()) {
                return FormValidation.error("API Token for Gitlab access required");
            }

            return FormValidation.ok();
        }

        public FormValidation doTestConnection(@QueryParameter("gitlabHostUrl") final String hostUrl,
                                               @QueryParameter("gitlabApiToken") final String token, @QueryParameter("ignoreCertificateErrors") final boolean ignoreCertificateErrors) throws IOException {
            try {
                GitLab.checkConnection(token, hostUrl, ignoreCertificateErrors);
                return FormValidation.ok("Success");
            } catch (IOException e) {
                return FormValidation.error("Client error : " + e.getMessage());
            }
        }

        public GitLab getGitlab() {
            if (gitlab == null) {
                gitlab = new GitLab();
            }
            return gitlab;
        }

        public String getGitlabApiToken() {
            return gitlabApiToken;
        }

        public String getGitlabHostUrl() {
            return gitlabHostUrl;
        }

        public boolean getIgnoreCertificateErrors() {
            return ignoreCertificateErrors;
        }

        public static DescriptorImpl get() {
            return Trigger.all().get(DescriptorImpl.class);
        }

    }

}
