@Library('corda-shared-build-pipeline-steps')
import static com.r3.build.BuildControl.killAllExistingBuildsForJob

killAllExistingBuildsForJob(env.JOB_NAME, env.BUILD_NUMBER.toInteger())

pipeline {
    agent { label 'k8s' }

    options {
        timestamps()
        ansiColor('xterm')
        overrideIndexTriggers(false)
        buildDiscarder(logRotator(daysToKeepStr: '7', artifactDaysToKeepStr: '7'))
        timeout(time: 3, unit: 'HOURS')
    }

    triggers {
        cron '@midnight'
    }

    environment {
        ARTIFACTORY_BUILD_NAME = "Corda / Publish / Publish Nightly to Artifactory"
    }

    stages {
        stage('Publish to Artifactory') {
            steps {
                rtServer (
                        id: 'R3-Artifactory',
                        url: 'https://software.r3.com/artifactory',
                        credentialsId: 'artifactory-credentials'
                )
                rtGradleDeployer (
                        id: 'deployer',
                        serverId: 'R3-Artifactory',
                        repo: 'corda-dev',
                )
                withCredentials([
                        usernamePassword(credentialsId: 'artifactory-credentials',
                                         usernameVariable: 'CORDA_ARTIFACTORY_USERNAME',
                                         passwordVariable: 'CORDA_ARTIFACTORY_PASSWORD')]) {
                    rtGradleRun (
                            usesPlugin: true,
                            useWrapper: true,
                            switches: "--no-daemon -s",
                            tasks: 'artifactoryPublish',
                            deployerId: 'deployer',
                            buildName: env.ARTIFACTORY_BUILD_NAME
                    )
                }
                rtPublishBuildInfo (
                        serverId: 'R3-Artifactory',
                        buildName: env.ARTIFACTORY_BUILD_NAME
                )
            }
        }
    }


    post {
        cleanup {
            deleteDir() /* clean up our workspace */
        }
    }
}

