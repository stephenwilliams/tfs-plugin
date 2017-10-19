//CHECKSTYLE:OFF
package hudson.plugins.tfs.util;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.*;
import hudson.slaves.NodeProperty;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import static hudson.plugins.git.util.GitUtils.workspaceToNode;

public class TfsUtils {
    public static EnvVars getPollEnvironment(AbstractProject p, FilePath ws, Launcher launcher, TaskListener listener)
            throws IOException, InterruptedException {
        return getPollEnvironment(p, ws, launcher, listener, true);
    }


    /**
     * An attempt to generate at least semi-useful EnvVars for polling calls, based on previous build.
     * Cribbed from various places.
     * @param p abstract project to be considered
     * @param ws workspace to be considered
     * @param launcher launcher to use for calls to nodes
     * @param listener build log
     * @param reuseLastBuildEnv true if last build environment should be considered
     * @return environment variables from previous build to be used for polling
     * @throws IOException on input or output error
     * @throws InterruptedException when interrupted
     */
    public static EnvVars getPollEnvironment(AbstractProject p, FilePath ws, Launcher launcher, TaskListener listener, boolean reuseLastBuildEnv)
            throws IOException,InterruptedException {
        EnvVars env = null;
        StreamBuildListener buildListener = new StreamBuildListener((OutputStream)listener.getLogger());
        AbstractBuild b = p.getLastBuild();

        if (b == null) {
            // If there is no last build, we need to trigger a new build anyway, and
            // GitSCM.compareRemoteRevisionWithImpl() will short-circuit and never call this code
            // ("No previous build, so forcing an initial build.").
            throw new IllegalArgumentException("Last build must not be null. If there really is no last build, " +
                    "a new build should be triggered without polling the SCM.");
        }

        if (reuseLastBuildEnv) {
            Node lastBuiltOn = b.getBuiltOn();

            if (lastBuiltOn != null) {
                Computer lastComputer = lastBuiltOn.toComputer();
                if (lastComputer != null) {
                    env = lastComputer.getEnvironment().overrideAll(b.getCharacteristicEnvVars());
                    for (NodeProperty nodeProperty : lastBuiltOn.getNodeProperties()) {
                        Environment environment = nodeProperty.setUp(b, launcher, (BuildListener) buildListener);
                        if (environment != null) {
                            environment.buildEnvVars(env);
                        }
                    }
                }
            }
            if (env == null) {
                env = p.getEnvironment(workspaceToNode(ws), listener);
            }

            p.getScm().buildEnvVars(b,env);
        } else {
            env = p.getEnvironment(workspaceToNode(ws), listener);
        }

        Jenkins jenkinsInstance = Jenkins.getInstance();
        if (jenkinsInstance == null) {
            throw new IllegalArgumentException("Jenkins instance is null");
        }
        String rootUrl = jenkinsInstance.getRootUrl();
        if(rootUrl!=null) {
            env.put("HUDSON_URL", rootUrl); // Legacy.
            env.put("JENKINS_URL", rootUrl);
            env.put("BUILD_URL", rootUrl+b.getUrl());
            env.put("JOB_URL", rootUrl+p.getUrl());
        }

        if(!env.containsKey("HUDSON_HOME")) // Legacy
            env.put("HUDSON_HOME", jenkinsInstance.getRootDir().getPath() );

        if(!env.containsKey("JENKINS_HOME"))
            env.put("JENKINS_HOME", jenkinsInstance.getRootDir().getPath() );

        if (ws != null)
            env.put("WORKSPACE", ws.getRemote());

        for (NodeProperty nodeProperty: jenkinsInstance.getGlobalNodeProperties()) {
            Environment environment = nodeProperty.setUp(b, launcher, (BuildListener)buildListener);
            if (environment != null) {
                environment.buildEnvVars(env);
            }
        }

        // add env contributing actions' values from last build to environment - fixes JENKINS-22009
        addEnvironmentContributingActionsValues(env, b);

        EnvVars.resolve(env);

        return env;
    }

    private static void addEnvironmentContributingActionsValues(EnvVars env, AbstractBuild b) {
        List<? extends Action> buildActions = b.getAllActions();
        if (buildActions != null) {
            for (Action action : buildActions) {
                // most importantly, ParametersAction will be processed here (for parameterized builds)
                if (action instanceof ParametersAction) {
                    ParametersAction envAction = (ParametersAction) action;
                    envAction.buildEnvVars(b, env);
                }
            }
        }

        // Use the default parameter values (if any) instead of the ones from the last build
        ParametersDefinitionProperty paramDefProp = (ParametersDefinitionProperty) b.getProject().getProperty(ParametersDefinitionProperty.class);
        if (paramDefProp != null) {
            for(ParameterDefinition paramDefinition : paramDefProp.getParameterDefinitions()) {
                ParameterValue defaultValue  = paramDefinition.getDefaultParameterValue();
                if (defaultValue != null) {
                    defaultValue.buildEnvironment(b, env);
                }
            }
        }
    }
}
