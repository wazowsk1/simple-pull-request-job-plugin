package io.jenkins.plugins.sprp;

import hudson.Launcher;
import hudson.model.Descriptor;
import io.jenkins.plugins.sprp.models.Agent;
import io.jenkins.plugins.sprp.models.ArtifactPublishingConfig;
import io.jenkins.plugins.sprp.models.Stage;
import io.jenkins.plugins.sprp.models.Step;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.casc.Configurator;
import org.jenkinsci.plugins.casc.ConfiguratorException;
import org.jenkinsci.plugins.casc.model.Mapping;
import org.jenkinsci.plugins.workflow.cps.Snippetizer;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PipelineSnippetGenerator {
    static private Logger logger = java.util.logging.Logger.getLogger(PipelineSnippetGenerator.class.getClass().getName());
    private Launcher launcher;

    PipelineSnippetGenerator(Launcher launcher){
        this.launcher = launcher;
    }

    public String shellScript(ArrayList<String> paths){
        StringBuilder snippet;
        snippet = new StringBuilder("script {\n" + "\tif (isUnix()) {\n");

        for(String p: paths)
            snippet.append("\t\tsh '").append(p).append(".sh").append("'\n");

        snippet.append("\t} else {\n");

        for(String p: paths)
            snippet.append("\t\tbat '").append(p).append(".bat").append("'\n");

        snippet.append("\t}\n" + "}\n");

        return snippet.toString();

    }

    // This function will add tabs at the beginning of each line
    public String addTabs(String script, int numberOfTabs){
        String tabs = StringUtils.repeat("\t", numberOfTabs);

        script = script.replace("\n", "\n" + tabs);
        if(script.length() > numberOfTabs)
            script = script.substring(0, script.length() - numberOfTabs);
        return script;
    }

    public String getTabString(int number){
        return StringUtils.repeat("\t", number);
    }

    private String getCommonOptionsOfAgent(Agent agent){
        String snippet = "";

        if (agent.getLabel() != null)
            snippet += "label '" + agent.getLabel() + "'\n";

        if (agent.getCustomWorkspace() != null)
            snippet += "customWorkspace '" + agent.getCustomWorkspace() + "'\n";

        if (agent.getDockerfile() != null || agent.getDockerImage() != null)
            snippet += "reuseNode " + agent.getReuseNode() + "\n";

        return snippet;
    }

    public String getAgent(Agent agent){
        String snippet = "";

        if(agent == null){
            snippet = "any\n";
        }
        else if(agent.getAnyOrNone() != null)
            snippet = agent.getAnyOrNone() + "\n";
        else {
            if(agent.getDockerImage() != null){
                snippet += "{\n";
                snippet += "\tdocker {\n";
                snippet += "\t\timage '" + agent.getDockerImage() + "'\n";

                if (agent.getArgs() != null)
                    snippet += "\t\targs '" + agent.getArgs() + "'\n";

                snippet += "\t\talwaysPull " + agent.getAlwaysPull() + "\n";
                snippet += "\t\t" + addTabs(getCommonOptionsOfAgent(agent), 2);
                snippet += "\t}\n";
                snippet += "}\n";
            }
            else if(agent.getDockerfile() != null){
                snippet += "{\n";
                snippet += "\tdockerfile {\n";
                snippet += "\t\tfilename '" + agent.getDockerfile() + "'\n";

                if (agent.getDir() != null)
                    snippet += "\t\tdir '" + agent.getDir() + "'\n";

                if (agent.getArgs() != null)
                    snippet += "\t\tadditionalBuildArgs '" + agent.getArgs() + "'\n";

                snippet += "\t\t" + addTabs(getCommonOptionsOfAgent(agent), 2);
                snippet += "\t}\n";
                snippet += "}\n";
            }
            else {
                snippet += "{\n";
                snippet += "\tnode{\n";
                snippet += "\t\t" + addTabs(getCommonOptionsOfAgent(agent), 2);
                snippet += "\t}\n";
                snippet += "}\n";
            }
        }

        return snippet;
    }

    public String getArchiveArtifactsSnippet(ArrayList<String> paths){
        StringBuilder snippet = new StringBuilder();

        for(String p: paths)
            snippet.append("archiveArtifacts artifacts: '").append(p).append("'\n");

        return snippet.toString();
    }

    public String getPublishReportSnippet(ArrayList<String> paths){
        StringBuilder snippet = new StringBuilder();

        for(String p: paths)
            snippet.append("junit '").append(p).append("'\n");

        return snippet.toString();
    }

    private String getSteps(ArrayList<Step> steps) throws InvocationTargetException, NoSuchMethodException, InstantiationException, ConfiguratorException, IllegalAccessException, NoSuchFieldException {
        StringBuilder snippet = new StringBuilder();

        snippet.append("\tsteps {\n");
        for(Step step: steps)
            snippet.append("\t\t").append(stepConfigurator(step));
        snippet.append("\t}\n");

        return snippet.toString();
    }

    public boolean isUnix(){
        return this.launcher.isUnix();
    }

    private String completeShellScriptPath(String scriptPath){
        if (isUnix()) {
            return scriptPath + ".sh";
        } else {
            return scriptPath + ".bat";
        }
    }

    private String stepConfigurator(Step step)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException,
            InstantiationException, ConfiguratorException, NoSuchFieldException {
        String snippet;
        Descriptor stepDescriptor = StepDescriptor.byFunctionName(step.getStepName());

        if(step.getStepName().equals("sh")){
            if(step.getDefaultParameter() != null) {
                step.setDefaultParameter(completeShellScriptPath(step.getDefaultParameter()));
            }
            else{
                step.getParameters().put("script", completeShellScriptPath(step.getParameters().get("script").toString()));
            }
        }

        if(stepDescriptor == null)
            throw new RuntimeException(new IllegalStateException("No step exist with the name of " + step.getStepName()));

        Class clazz = stepDescriptor.clazz;

        Object stepObject = null;

        // Right now all the DefaultParameter of a step are considered to be string.
        if(step.getDefaultParameter() != null){
            Constructor c = clazz.getConstructor(String.class);
            stepObject = c.newInstance(step.getDefaultParameter());
        }
        else{
            Mapping mapping = new Mapping();

            for(Map.Entry<String, Object> entry: step.getParameters().entrySet()){
                Class stepFieldClass = clazz.getDeclaredField(entry.getKey()).getType();

                if(stepFieldClass == String.class)
                    mapping.put(entry.getKey(), (String) entry.getValue());
                else if(stepFieldClass == Boolean.class)
                    mapping.put(entry.getKey(), (Boolean) entry.getValue());
                else if(stepFieldClass == Float.class)
                    mapping.put(entry.getKey(), (Float) entry.getValue());
                if(stepFieldClass == Double.class)
                    mapping.put(entry.getKey(), (Double) entry.getValue());
                else if(stepFieldClass == Integer.class)
                    mapping.put(entry.getKey(), (Integer) entry.getValue());
                else
                    logger.log(Level.WARNING, stepFieldClass.getName() + "is not supported at this time.");
            }

            Configurator configurator = Configurator.lookup(clazz);
            if (configurator != null) {
                stepObject = configurator.configure(mapping);
            }
            else{
                throw new IllegalStateException("No step with name '" + step.getStepName() +
                        "' exist. Have you installed required plugin.");
            }
        }

        snippet = Snippetizer.object2Groovy(stepObject) + "\n";
        return snippet;
    }

    public String getStage(
            Stage stage,
            ArrayList<String> buildResultPaths,
            ArrayList<String> testResultPaths,
            ArrayList<String> archiveArtifacts,
            GitConfig gitConfig,
            String findbugs
    ) throws NoSuchMethodException, InstantiationException, IllegalAccessException, ConfiguratorException,
            InvocationTargetException, NoSuchFieldException
    {
        String snippet = "stage('" + stage.getName() + "') {\n";

        snippet += "\tsteps {\n";
        snippet += "\t\t" + addTabs(getSteps(stage.getSteps()), 2);
        snippet += "\t}\n";

        // This condition is to generate Success, Failure and Always steps after steps finished executing.
        if(stage.getFailure() != null
                || stage.getSuccess() != null
                || stage.getAlways() != null
                || (stage.getName().equals("Build") &&
                        (archiveArtifacts != null || buildResultPaths != null || findbugs != null))
                || stage.getName().equals("Tests") && (testResultPaths != null || gitConfig.getGitUrl() != null)) {
            snippet += "\tpost {\n";

            if (stage.getSuccess() != null
                    || (stage.getName().equals("Build"))
                    || stage.getName().equals("Tests") && (testResultPaths != null)// || gitConfig.getGitUrl() != null)
                    )
            {
                snippet += "\t\tsuccess {\n";
                if (stage.getName().equals("Build")) {
                    snippet += "\t\t\t" + addTabs("archiveArtifacts artifacts: '**/target/*.jar'\n", 3);
                    if(archiveArtifacts != null)
                        snippet += "\t\t\t" + addTabs(getArchiveArtifactsSnippet(archiveArtifacts), 3);

                    if(buildResultPaths != null)
                        snippet += "\t\t\t" + addTabs(getPublishReportSnippet(buildResultPaths), 3);
                }
                if (stage.getName().equals("Tests")) {
                    if(testResultPaths != null)
                        snippet += "\t\t\t" + addTabs(getPublishReportSnippet(testResultPaths), 3);
//                    TODO Abhishek: code is commented out for testing purposes, it will be reinstated later
//                    if(gitConfig.getGitUrl() != null)
//                        snippet += "\t\t\t" + addTabs("gitPush " +
//                                "credentialId: \"" + gitConfig.getCredentialsId() + "\"," +
//                                "url: \"" + gitConfig.getGitUrl() + "\"," +
//                                "branch: \"" + gitConfig.getGitBranch() + "\"" +
//                                "\n", 3);
                }
                if(stage.getSuccess() != null)
                    snippet += "\t\t\t" + addTabs(shellScript(stage.getSuccess()), 3);
                snippet += "\t\t}\n";
            }
            if (stage.getAlways() != null || (findbugs != null && stage.getName().equals("Tests"))) {
                snippet += "\t\talways {\n";
                if(findbugs != null && stage.getName().equals("Tests"))
                    snippet += "\t\t\t" + addTabs("findbugs pattern: '" + findbugs + "'\n", 3);

                if(stage.getAlways() != null)
                    snippet += "\t\t\t" + addTabs(shellScript(stage.getAlways()), 3);
                snippet += "\t\t}\n";
            }
            if (stage.getFailure() != null) {
                snippet += "\t\tfailure {\n";
                snippet += "\t\t\t" + addTabs(shellScript(stage.getFailure()), 3);
                snippet += "\t\t}\n";
            }

            snippet += "\t}\n";
        }
        snippet += "}\n";

        return snippet;
    }

    public String getPublishArtifactStage(ArtifactPublishingConfig config,
                                          ArrayList<HashMap<String, String>> publishArtifacts){
        if(config == null)
            return "";

        StringBuilder snippet = new StringBuilder("stage('Publish Artifact') {\n");

        snippet.append("\tsteps {\n");
        snippet.append("\t\t" + "withCredentials([file(credentialsId: '").append(config.getCredentialId()).append("', variable: 'FILE')]) {\n");

        for(HashMap<String, String> artifact: publishArtifacts){
            snippet.append("\t\t\tsh 'scp -i $FILE ").append(artifact.get("from")).append(" ").append(config.getUser()).append("@").append(config.getHost()).append(":").append(artifact.get("to")).append("'\n");
        }

        snippet.append("\t\t}\n");
        snippet.append("\t}\n");
        snippet.append("}\n");

        return snippet.toString();
    }
}
