# tics-jenkins-plugin
The TICS Jenkins plugin adds two independent actions to Jenkins that can be used in your Jenkins jobs:

* [Run TICS](#run-tics) is a build step that invokes TICS as part of your Jenkins job. Although you could also run TICS via the "Execute shell" build step that is part of the standard Jenkins installation, the Run TICS step helps you to set the most commonly used TICS options in an easy-to-use UI.
* [Publish TICS](#publish-tics) Results is a post-build step that retrieves TQI scores and deltas from the TICS viewer and puts a TQI statement on the Jenkins job front-page. Note that you do not have to add a Run TICS build step in order to use the Publish TICS Results build step.
Both steps can be invoked through the classic Jenkins UI or through pipelines. Pipeline syntax for declarative pipelines is explained [below](tics-through-declarative-pipelines).

Before you can use either step you need to install the TICS Jenkins plugin.

# Run TICS
This build step runs TICS as part of your Jenkins job. Requirements:

* You need to have TICS installed on the machine on which you want to run the job.

To configure this build step:

* Click the "Add build step" button in your Jenkins job of the project you want to analyze and select "Run TICS"
* Provide the required settings. Click the question mark icon for each option to get help.
* More advanced options become available when you click on the "Advanced..." button.
* Save the settings and choose Build Now to execute the job.

# Publish TICS
![Jenkins-TICS-Plugin-Project](/src/main/webapp/jenkins-tics-plugin-project.png)
Requirements:

* You need to have a TICS Viewer running somewhere on the network that is HTTP accessible by the machine on which you want to run the job.

To configure this post-build step:

* Click the "Add post-build action" button in your Jenkins job of the project you want to get results for and select "Publish TICS results"
* Provide the required settings. Click the question mark icon for each option to get help.
* Save the settings and choose Build Now to execute the job.

# TICS through declarative pipelines
Below is an example of executing a Run TICS build step through declarative pipelines. The syntax of scripted pipelines is similar. For more information on the differences between scripted and declarative pipelines please refer to this article: https://jenkins.io/doc/book/pipeline/


      pipeline {
        agent any
          environment {
                // Environment variables defined in this block will not be passed to the TICS plugin during a TICS analysis. This is due to a Jenkins issue (JENKINS-29144).
                // Please define any environment variables as part of the environmentVariables parameter, which is shown below.
                ...
          }
          stages {
            stage('Run Tics') {
              steps {
                runTics (
                  projectName: 'projectName',                     // Mandatory parameter (case sensitive)
                  branchName: 'master',                           // Mandatory parameter (case sensitive)

                  calc: ['INCLUDERELATIONS', 'PREPARE', 'LOC'],   // Optional parameter. Example of metrics that will be analyzed with 'calc'.
                  recalc: ['ABSTRACTINTERPRETATION', 'SECURITY'], // Optional parameter. Example of metrics that will be analyzed with 'recalc'.

                  // ticsBin is an Optional parameter.
                  // If the TICS executables (TICSMaintenance, TICSQServer) can be found in the PATH environment variable, this parameter can be skipped.
                  ticsBin: 'C:/Program Files (x86)/TIOBE/TICS/BuildServer',
                  ticsConfiguration: 'C:/Program Files (x86)/TIOBE/TICS/FileServer/cfg',    // Optional parameter
                  environmentVariables: [                                                   // Optional parameter
                      "TICSCHKPATH" : "C:/Program Files (x86)/TIOBE/TICS/FileServer/chk",
                      ...
                  ],
                  extraArguments: '',                                                       // Optional parameter
                  tmpdir: '',                                                               // Optional parameter
                  branchDirectory: '',                                                      // Optional parameter
                )
              }
            }
          }
          // Other 'stages'.
      }
        
Below is an example of executing Publish TICS results (TQI label) post build step through declarative pipelines:


      pipeline {
        agent any
          stages {
            stage('Publish results') {
              steps {
                publishTicsResults (
                  projectName: 'projectName',                               // Mandatory parameter (case sensitive)
                  branchName: 'master',                                     // Mandatory parameter (case sensitive)
                  viewerUrl: 'http://www.company.com:42506/tiobeweb/TICS',  // Mandatory parameter
                  checkQualityGate: false,                                  // Optional boolean parameter that defaults to false if not set. Enables TICS Quality Gate checks.
                  failIfQualityGateFails: false,                            // Optional boolean parameter that defaults to false if not set. Marks the build as failure if TICS Quality Gate fails for any reason.

                  // 'userName' and 'userId' are used to specify the credentials to be used when accessing the viewer.
                  // Those are only needed if the project specified in projectPath requires authentication.
                  // These credentials are managed by the credentials plugin: https://plugins.jenkins.io/credentials
                  // If the project requires authentication, please specify one or the other. userId is preferred as it is normally a unique id.
                  userName: '',                                             // Optional parameter
                  userId: '',                                               // Optional parameter

                  // Advanced parameters:
                  ticsProjectPath: 'HIE://PROJECT/BRANCH/COMPONENT',        // Optional parameter that can be used instead of 'projectName' and 'branchName'
                )
              }
            }
            // Other 'stages'.
        }
      }

Notes on pipelines

* The available metrics that can be given as input to 'calc'/'recalc' for the TICS analysis ('runTics') through scripted/declarative pipelines are:

      ALL, PREPARE, FINALIZE, CHANGEDFILES, BUILDRELATIONS, INCLUDERELATIONS, TOTALTESTCOVERAGE, SYSTEMTESTCOVERAGE, 
      INTEGRATIONTESTCOVERAGE, UNITTESTCOVERAGE, CODINGSTANDARD, COMPILERWARNING, ABSTRACTINTERPRETATION, SECURITY, 
      AVGCYCLOMATICCOMPLEXITY, MAXCYCLOMATICCOMPLEXITY, FANOUT, DEADCODE, DUPLICATEDCODE, LOC, ELOC, GLOC, CHANGERATE, 
      LINESADDED, LINESDELETED, LINESCHANGED, ACCUCHANGERATE, ACCULINESADDED, ACCULINESDELETED, ACCULINESCHANGED, 
      FIXRATE, ACCUFIXRATE
      
If the stage/steps are not defined correctly, and the TICS analysis and/or TICS Publish fails for any reason, **an exception will be thrown**, and the entire pipeline run will be stopped. If you want for the pipeline run to continue, even if TICS fails to publish/run, you can surround the TICS stage/steps with a try/catch block. For example:

    try {
      stage('Publish results') {
        publishTics (
          // arguments
        )
    }
    catch (e) {
      // continue execution
    }
