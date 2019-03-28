package org.jfrog.hudson.trigger;

import antlr.ANTLRException;
import hudson.Extension;
import hudson.maven.MavenModuleSet;
import hudson.model.*;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.ListBoxModel;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jfrog.build.api.util.NullLog;
import org.jfrog.build.client.ArtifactoryHttpClient;
import org.jfrog.build.client.ItemLastModified;
import org.jfrog.hudson.ArtifactoryServer;
import org.jfrog.hudson.CredentialsConfig;
import org.jfrog.hudson.DeployerOverrider;
import org.jfrog.hudson.ServerDetails;
import org.jfrog.hudson.util.CredentialManager;
import org.jfrog.hudson.util.Credentials;
import org.jfrog.hudson.util.JenkinsBuildInfoLog;
import org.jfrog.hudson.util.RepositoriesUtils;
import org.jfrog.hudson.util.plugins.PluginsUtils;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.text.ParseException;
import java.util.List;
import java.util.logging.Logger;

/**
 * @author Alexei Vainshtein
 */
public class ArtifactoryTrigger extends Trigger implements DeployerOverrider {
    private static final Logger logger = Logger.getLogger(JenkinsBuildInfoLog.class.getName());

    private final CredentialsConfig deployerCredentialsConfig;

    private String path;
    private ServerDetails details;
    private long lastModified = System.currentTimeMillis();

    @DataBoundConstructor
    public ArtifactoryTrigger(String path, String spec, CredentialsConfig deployerCredentialsConfig, ServerDetails details) throws ANTLRException {
        super(spec);
        this.path = path;
        this.deployerCredentialsConfig = deployerCredentialsConfig;
        this.details = details;
    }

    @Override
    public void run() {
        if (job == null) {
            return;
        }

        ArtifactoryServer artifactoryServer = RepositoriesUtils.getArtifactoryServer(details.getArtifactoryName(), RepositoriesUtils.getArtifactoryServers());
        if (artifactoryServer == null) {
            logger.warning("Artifactory server " + details.getArtifactoryName() + " doesn't exists.");
            return;
        }

        CredentialsConfig credentialsConfig = CredentialManager.getPreferredDeployer(this, artifactoryServer);

        ArtifactoryHttpClient client = new ArtifactoryHttpClient(artifactoryServer.getUrl(),
                credentialsConfig.provideUsername(job), credentialsConfig.providePassword(job),
                new NullLog());
        try {
            ItemLastModified itemLastModified = client.getItemLastModified(path);
                long responseLastModified = itemLastModified.getLastModified();
                if (responseLastModified > lastModified) {
                    this.lastModified = responseLastModified;
                    if (job instanceof Project) {
                        AbstractProject<?, ?> project = ((Project) job).getRootProject();
                        saveAndSchedule(itemLastModified, project);
                        return;
                    }

                    if (job instanceof MavenModuleSet) {
                        AbstractProject project = ((MavenModuleSet)job).getRootProject();
                        saveAndSchedule(itemLastModified, project);
                        return;
                    }

                    if (job instanceof WorkflowJob) {
                        WorkflowJob project = (WorkflowJob) job;
                        logger.fine("Updating " + job.getName());
                        project.save();
                        project.scheduleBuild(new ArtifactoryCause(itemLastModified.getUri()));
                    }
                } else {
                    logger.fine(String.format("Artifactory trigger did not trigger job %s, since last modified time: %d is earlier or equal than %d for path %s", job.getName(), responseLastModified, lastModified, path));
                }
        } catch (IOException | ParseException e) {
            logger.severe("Received an error: " + e.getMessage());
            logger.fine("Received an error: " + e);
        }
    }

    private void saveAndSchedule(ItemLastModified itemLastModified, AbstractProject project) throws IOException {
        logger.fine("Updating " + job.getName());
        project.save();
        project.scheduleBuild(new ArtifactoryCause(itemLastModified.getUri()));
    }

    @Override
    public void stop() {
        if (job != null) {
            logger.info("Stopping " + job.getName() + " Artifactory trigger.");
        }
        super.stop();
    }

    public String getPath() {
        return path;
    }

    public ServerDetails getDetails() {
        return details;
    }

    public String getArtifactoryName() {
        return getDetails() != null ? getDetails().artifactoryName : null;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Override
    public boolean isOverridingDefaultDeployer() {
        return deployerCredentialsConfig != null && deployerCredentialsConfig.isCredentialsProvided();
    }

    @Override
    public Credentials getOverridingDeployerCredentials() {
        return null;
    }

    @Override
    public CredentialsConfig getDeployerCredentialsConfig() {
        return deployerCredentialsConfig;
    }

    @Extension
    public static final class DescriptorImpl extends TriggerDescriptor {

        public String getDisplayName() {
            return "Enable Artifactory trigger";
        }

        public boolean isApplicable(Item item) {
            return (item instanceof WorkflowJob || item instanceof Project || item instanceof MavenModuleSet);
        }

        public List<ArtifactoryServer> getArtifactoryServers() {
            return RepositoriesUtils.getArtifactoryServers();
        }

        @SuppressWarnings("unused")
        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item project) {
            return PluginsUtils.fillPluginCredentials(project);
        }
    }
}