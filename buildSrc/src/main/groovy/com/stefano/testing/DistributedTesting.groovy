package com.stefano.testing

import com.bmuschko.gradle.docker.DockerRegistryCredentials
import com.bmuschko.gradle.docker.tasks.container.DockerCreateContainer
import com.bmuschko.gradle.docker.tasks.container.DockerLogsContainer
import com.bmuschko.gradle.docker.tasks.container.DockerStartContainer
import com.bmuschko.gradle.docker.tasks.container.DockerWaitContainer
import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage
import com.bmuschko.gradle.docker.tasks.image.DockerCommitImage
import com.bmuschko.gradle.docker.tasks.image.DockerPushImage
import com.bmuschko.gradle.docker.tasks.image.DockerTagImage
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.testing.Test

/*
this plugin is responsible for setting up all the required docker image building tasks required for producing and pushing an
image of the current build output to a remote container registry
 */

class ImageBuilding implements Plugin<Project> {

    DockerPushImage pushTask

    @Override
    void apply(Project project) {
        DockerBuildImage buildDockerImageForSource = project.tasks.create('buildDockerImageForSource', DockerBuildImage) {
            dependsOn project.rootProject.getTasksByName("clean", true)
            inputDir.set(new File("."))
            dockerFile.set(new File(new File("testing"), "Dockerfile"))
        }

        DockerCreateContainer createBuildContainer = project.tasks.create('createBuildContainer', DockerCreateContainer) {
            File gradleDir = new File((System.getProperty("java.io.tmpdir") + File.separator + "gradle"))
            File mavenDir = new File((System.getProperty("java.io.tmpdir") + File.separator + "maven"))
            doFirst {
                if (!gradleDir.exists()) {
                    gradleDir.mkdirs()
                }
                if (!mavenDir.exists()) {
                    mavenDir.mkdirs()
                }
            }

            dependsOn buildDockerImageForSource
            targetImageId buildDockerImageForSource.getImageId()
            binds = [(gradleDir.absolutePath): "/tmp/gradle", (mavenDir.absolutePath): "/home/root/.m2"]
        }

        DockerStartContainer startBuildContainer = project.tasks.create('startBuildContainer', DockerStartContainer) {
            dependsOn createBuildContainer
            targetContainerId createBuildContainer.getContainerId()
        }

        DockerLogsContainer logBuildContainer = project.tasks.create('logBuildContainer', DockerLogsContainer) {
            dependsOn startBuildContainer
            targetContainerId createBuildContainer.getContainerId()
            follow = true
        }

        DockerWaitContainer waitForBuildContainer = project.tasks.create('waitForBuildContainer', DockerWaitContainer) {
            dependsOn logBuildContainer
            targetContainerId createBuildContainer.getContainerId()
        }

        DockerCommitImage commitBuildImageResult = project.tasks.create('commitBuildImageResult', DockerCommitImage) {
            dependsOn waitForBuildContainer
            targetContainerId createBuildContainer.getContainerId()
        }

        DockerTagImage tagBuildImageResult = project.tasks.create('tagBuildImageResult', DockerTagImage) {
            dependsOn commitBuildImageResult
            imageId = commitBuildImageResult.getImageId()
            tag = "${UUID.randomUUID().toString().toLowerCase().subSequence(0, 12)}"
            repository = "stefanotestingcr.azurecr.io/testing"
        }

        if (!System.hasProperty("docker.image") && System.hasProperty("docker.tag")) {
            DockerPushImage pushBuildImage = project.tasks.create('pushBuildImage', DockerPushImage) {
                dependsOn tagBuildImageResult
                doFirst {
                    registryCredentials = new DockerRegistryCredentials(project.getObjects())
                    registryCredentials.username.set("stefanotestingcr")
                    registryCredentials.password.set(System.getProperty("docker.push.password") ? System.getProperty("docker.push.password") : "")
                }
                imageName = "stefanotestingcr.azurecr.io/testing"
                tag = tagBuildImageResult.tag
            }
            this.pushTask = pushBuildImage
        } else {
            DockerPushImage pushBuildImage = project.tasks.create('pushBuildImage', DockerPushImage) {
                doFirst {
                    registryCredentials = new DockerRegistryCredentials(project.getObjects())
                    registryCredentials.username.set("stefanotestingcr")
                    registryCredentials.password.set(System.getProperty("docker.push.password") ? System.getProperty("docker.push.password") : "")
                }
                imageName = System.getProperty("docker.image")
                tag = System.getProperty("docker.tag")
            }
            this.pushTask = pushBuildImage
        }

    }
}
/*
This plugin is responsible for wiring together the various components of test task modification
 */

class DistributedTesting implements Plugin<Project> {

    static def getPropertyAsInt(Project proj, String property, Integer defaultValue) {
        return proj.hasProperty(property) ? Integer.parseInt(proj.property(property).toString()) : defaultValue
    }

    @Override
    void apply(Project project) {
        ensureImagePluginIsApplied(project)
        ImageBuilding imagePlugin = project.plugins.getPlugin(ImageBuilding)
        DockerPushImage imageBuildingTask = imagePlugin.pushTask

        //in each subproject
        //1. add the task to determine all tests within the module
        //2. modify the underlying testing task to use the output of the listing task to include a subset of tests for each fork
        //3. KubesTest will invoke these test tasks in a parallel fashion on a remote k8s cluster
        project.subprojects { Project subProject ->
            subProject.tasks.withType(Test) { Test task ->
                ListTests testListerTask = createTestListingTasks(task, subProject)
                Test modifiedTestTask = modifyTestTaskForParallelExecution(subProject, task, testListerTask)
                KubesTest parallelTestTask = generateParallelTestingTask(subProject, task, imageBuildingTask)
            }
        }

        //now we are going to create "super" groupings of these KubesTest tasks, so that it is possible to invoke all submodule tests with a single command
        //group all kubes tests by their underlying target task (test/integrationTest/smokeTest ... etc)
        Map<String, List<KubesTest>> allKubesTestingTasksGroupedByType = project.subprojects.collect { prj -> prj.getAllTasks(false).values() }
                .flatten()
                .findAll { task -> task instanceof KubesTest }
                .groupBy { task -> task.taskToExecuteName }

        //first step is to create a single task which will invoke all the submodule tasks for each grouping
        //ie allParallelTest will invoke [node:test, core:test, client:rpc:test ... etc]
        //ie allIntegrationTest will invoke [node:integrationTest, core:integrationTest, client:rpc:integrationTest ... etc]
        createGroupedParallelTestTasks(allKubesTestingTasksGroupedByType, project, imageBuildingTask)
    }

    private List<Task> createGroupedParallelTestTasks(Map<String, List<KubesTest>> allKubesTestingTasksGroupedByType, Project project, DockerPushImage imageBuildingTask) {
        allKubesTestingTasksGroupedByType.entrySet().collect { entry ->
            def taskType = entry.key
            def allTasksOfType = entry.value
            def allParallelTask = project.rootProject.tasks.create("allParallel" + taskType.capitalize(), KubesTest) {
                dependsOn imageBuildingTask
                printOutput = true
                fullTaskToExecutePath = allTasksOfType.collect { task -> task.fullTaskToExecutePath }.join(" ")
                taskToExecuteName = taskType
                doFirst {
                    dockerTag = imageBuildingTask.imageName.get() + ":" + imageBuildingTask.tag.get()
                }
            }

            //second step is to create a task to use the reports output by the parallel test task
            def reportOnAllTask = project.rootProject.tasks.create("reportAllParallel${taskType.capitalize()}", KubesReporting) {
                dependsOn allParallelTask
                destinationDir new File(project.rootProject.getBuildDir(), "allResults${taskType.capitalize()}")
                doFirst {
                    destinationDir.deleteDir()
                    podResults = allParallelTask.containerResults
                    reportOn(allParallelTask.testOutput)
                }
            }

            //invoke this report task after parallel testing
            allParallelTask.finalizedBy(reportOnAllTask)
            project.logger.info "Created task: ${allParallelTask.getPath()} to enable testing on kubenetes for tasks: ${allParallelTask.fullTaskToExecutePath}"
            project.logger.info "Created task: ${reportOnAllTask.getPath()} to generate test html output for task ${allParallelTask.getPath()}"
            return allParallelTask

        }
    }

    private KubesTest generateParallelTestingTask(Project projectContainingTask, Test task, DockerPushImage imageBuildingTask) {
        def taskName = task.getName()
        def capitalizedTaskName = task.getName().capitalize()

        KubesTest createdParallelTestTask = projectContainingTask.tasks.create("parallel" + capitalizedTaskName, KubesTest) {
            dependsOn imageBuildingTask
            printOutput = true
            fullTaskToExecutePath = task.getPath()
            taskToExecuteName = taskName
            doFirst {
                dockerTag = imageBuildingTask.imageName.get() + ":" + imageBuildingTask.tag.get()
            }
        }
        projectContainingTask.logger.info "Created task: ${createdParallelTestTask.getPath()} to enable testing on kubenetes for task: ${task.getPath()}"
        return createdParallelTestTask as KubesTest
    }

    private Test modifyTestTaskForParallelExecution(Project subProject, Test task, ListTests testListerTask) {
        subProject.logger.info("modifying task: ${task.getPath()} to depend on task ${testListerTask.getPath()}")
        def reportsDir = new File(new File(subProject.rootProject.getBuildDir(), "test-reports"), subProject.name + "-" + task.name)
        task.configure {
            dependsOn testListerTask
            binResultsDir new File(reportsDir, "binary")
            reports.junitXml.destination new File(reportsDir, "xml")
            maxHeapSize = "6g"
            doFirst {
                filter {
                    def fork = getPropertyAsInt(subProject, "dockerFork", 0)
                    def forks = getPropertyAsInt(subProject, "dockerForks", 1)
                    def shuffleSeed = 42
                    subProject.logger.info("requesting tests to include in testing task ${task.getPath()} (${fork}, ${forks}, ${shuffleSeed})")
                    List<String> includes = testListerTask.getTestsForFork(
                            fork,
                            forks,
                            shuffleSeed)
                    subProject.logger.info "got ${includes.size()} tests to include into testing task ${task.getPath()}"

                    if (includes.size() == 0) {
                        subProject.logger.info "Disabling test executing for testing task ${task.getPath()}"
                        excludeTestsMatching "*"
                    }

                    includes.forEach { include ->
                        subProject.logger.info "including: $include for testing task ${task.getPath()}"
                        includeTestsMatching include
                    }
                    failOnNoMatchingTests false
                }
            }
        }

        return task
    }

    private static void ensureImagePluginIsApplied(Project project) {
        if (!project.plugins.hasPlugin(ImageBuilding)) {
            project.plugins.apply(ImageBuilding)
        }
    }

    private ListTests createTestListingTasks(Test task, Project subProject) {
        def taskName = task.getName()
        def capitalizedTaskName = task.getName().capitalize()
        //determine all the tests which are present in this test task.
        //this list will then be shared between the various worker forks
        def createdListTask = subProject.tasks.create("listTestsFor" + capitalizedTaskName, ListTests) {
            //the convention is that a testing task is backed by a sourceSet with the same name
            dependsOn subProject.getTasks().getByName("${taskName}Classes")
            doFirst {
                //we want to set the test scanning classpath to only the output of the sourceSet - this prevents dependencies polluting the list
                scanClassPath = task.getTestClassesDirs() ? task.getTestClassesDirs() : Collections.emptyList()
            }
        }

        //convenience task to utilize the output of the test listing task to display to local console, useful for debugging missing tests
        def createdPrintTask = subProject.tasks.create("printTestsFor" + capitalizedTaskName) {
            dependsOn createdListTask
            doLast {
                createdListTask.getTestsForFork(
                        getPropertyAsInt(subProject, "dockerFork", 0),
                        getPropertyAsInt(subProject, "dockerForks", 1),
                        42).forEach { testName ->
                    println testName
                }
            }
        }

        subProject.logger.info("created task: " + createdListTask.getPath() + " in project: " + subProject + " it dependsOn: " + createdListTask.dependsOn)
        subProject.logger.info("created task: " + createdPrintTask.getPath() + " in project: " + subProject + " it dependsOn: " + createdPrintTask.dependsOn)

        return createdListTask as ListTests
    }

}
