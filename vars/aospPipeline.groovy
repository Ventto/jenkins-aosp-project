#!/usr/bin/groovy

/**
 * [ NAME ]
 *
 * CI Pipeline template for AOSP projects
 *
 * [ SYNOPSYS ]
 *
 * ```
 * aospPipeline {
 *     manifestUrl   = "URL"
 *     targetProduct = "TARGET"
 *     [OPTION,...]
 * }
 * ```
 *
 * [ REQUIREMENTS ]
 *
 * This pipeline is designed for AOSP projects; It requires the following
 * package on the system:
 * - *repo*
 *
 * It requires the following environment variables:
 *
 * |  Name                                | Description
 * ---------------------------------------------------------------------------
 * - NODE_LABEL                           | label of the node, on which, we'll
 *                                        | run the pipeline
 *
 * Set the environment variables in the `Jenkinsfile` as following:
 *
 * ```
 * env = [
 *     "NODE_LABEL={node-label}"
 * ]
 *
 * withEnv(env) {
 *     aospPipeline {
 *         [...]
 *     }
 * }
 * ```
 *
 * [ OPTIONS ]
 *
 * Mandatory Arguments:
 *
 * - manifestUrl       URL of the manifest Git repository
 * - targetProduct     name of the target without building variant
 *
 * Optional:

 * |  Name           | Type         | Default Value
 * ---------------------------------------------------------------------------
 * - buildVariant    | String       | "eng"
 * - aospDir         | String       | "aosp"
 * - repoBranch      | String       | Empty
 * - jobCpus         | Integer      | 0
 * - ccacheEnabled   | Boolean      | false
 * - ccacheSize      | String       | "50G"
 * - logcatEnabled   | Boolean      | false
 * - emulatorEnabled | Boolean      | false
 * - emulatorOpts    | String       | Empty
 * - ctsTests        | String Array | Empty
 * - skipStages      | Maps         | all values are false
 * - mailTo          | String       | If any stage failure occurs, it sends
                     |              | an email to the given addresses (separated
                     |              | with a space character); if `mailTo` is not
                     |              | set, it will not send a mail
                     |              |
 * - mailCc          | String       | Can be used in conjunction with the
 *                   |              | `mailTo` option
 *
 * `skipStages` Maps has the following keys:
 *
 * |  Name       | Type    | Description
 * ---------------------------------------------------------------------------
 * - scm         | Boolean | If true, skips 'SCM' step
 * - build       | Boolean | If true, skips 'Build' step
 * - statictests | Boolean | If true, skips 'Static Analysis' step
 * - cts         | Boolean | If true, skips 'CTS' step
 * - sonarqube   | Boolean | If true, skips 'Sonarqube' step
 */
def call(body)
{
    def args = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = args
    body()

    // ============================
    //          Parameters
    // ============================

    if (!env.NODE_LABEL) {
        currentBuild.result = 'ABORTED'
        error("${currentBuild.result}: you must specify a node to run the pipeline on")
    }

    if (!args.targetProduct || !args.manifestUrl) {
        currentBuild.result = 'ABORTED'
        error("${currentBuild.result}: missing arguments")
    }

    if (args.skipStages && !args.skipStages.sonarqube && !args.sonarSettings) {
        currentBuild.result = 'ABORTED'
        error("${currentBuild.result}: either 'sonarSetting' parameter must be set or skip 'sonarqube' step")
    }

    /* Set the workspace's subdirectory where the AOSP sources are */
    args.buildVariant = (args.buildVariant) ? args.buildVariant : "eng"
    args.aospDir = (args.aospDir) ? args.aospDir : "aosp"
    args.repoBranch = (args.repoBranch) ? "-b ${args.repoBranch}" : ""
    args.jobCpus = (args.jobCpus) ? args.jobCpus : 0
    args.ccacheEnabled = (args.ccacheEnabled) ? args.ccacheEnabled : false
    args.ccacheSize = (args.ccacheSize) ? args.ccacheSize : "50G"
    args.logcatEnabled = (args.logcatEnabled) ? args.logcatEnabled : false
    args.ctsTests = (args.ctsTests) ? args.ctsTests : []

    /* A virtual device normally occupies a pair of adjacent ports:
     * a console port and an adb port. The console of the first
     * virtual device running on a particular machine uses console
     * port 5554 and adb port 5555. Subsequent instances use port
     * numbers increasing by two — for example, 5556/5557, 5558/5559,
     * and so on. The range is 5554 to 5682, allowing for 64
     * concurrent virtual devices.
     * Default port: 5566/5567
     */
    def emulatorOpts = "-no-window -memory 1024 -accel on -no-snapshot -port 5566"
    args.emulatorOpts = (args.emulatorOpts) ? args.emulatorOpts.join(' ') : emulatorOpts
    args.emulatorEnabled = (args.emulatorEnabled) ? args.emulatorEnabled : false

    def skipStages = [
        scm          : false,
        build        : false,
        statictests  : false,
        cts          : false,
        sonarqube    : false,
    ]

    skipStages = (args.skipStages) ? args.skipStages : skipStages

    /* Set the `USE_CCACHE` environment variable to enable building cache */
    def use_ccache = (args.ccacheEnabled) ? 1 : 0

    // ============================
    //          Variables
    // ============================

    // The ccache binary is either for `linux-86` or `darwin-x86`
    def CCACHE_BIN = "prebuilts/misc/linux-x86/ccache/ccache"

    /*
     * Initialize properties, avoiding to get
     * "No such property" error into declarative post action
     * at the end of pipeline.
     */
    def emulatorPid = null
    def logcatPid = null

    pipeline {
        // Jenkins will run that pipeline on a given specific node
        agent { node { label env.NODE_LABEL } }

        /*
         * Prepend all console output generated by the Pipeline run with the
         * time at which the line was emitted
         */
        options {
            timestamps()
            disableConcurrentBuilds()
        }

        environment {
            /*
             * The AOSP wrapper's syntax:
             *  ```
             *      aosp {        |      aosp('aosp/', 'aosp_x86_64-eng') {
             *         ...        |         ...
             *      }             |      }
             *  ```
             * This wrapper enables to call any command within an AOSP setup
             * environment regarding the target build (ex: aosp_x86_64-eng).
             * The `AOSP_ROOT` and `AOSP_TARGET_BUILD` are required if calling
             * the wrapper without arguments.
             */
            AOSP_ROOT         = "${args.aospDir}"
            AOSP_TARGET_BUILD = "${args.targetProduct}-${args.buildVariant}"

            LOG_DIR      = "${WORKSPACE}/logs/${JOB_NAME}-${BUILD_NUMBER}"
            LOG_EMULATOR = "${LOG_DIR}/emulator.log"
            ERR_EMULATOR = "${LOG_DIR}/emulator-err.log"
            LOG_LOGCAT   = "${LOG_DIR}/logcat.log"
            ERR_LOGCAT   = "${LOG_DIR}/logcat-err.log"

            /*
             * Set `USE_CCACHE` for specifing to use the 'ccache' compiler
             * cache, which will speed up things once you have built things
             * a first time (1: enable, 0: disable).
             */
            USE_CCACHE           = "${use_ccache}"
        }

        stages {
            stage('Pre Actions') {
                steps {
                    sh """ mkdir -p "${LOG_DIR}" "${args.aospDir}" """
                }
            }
            stage('SCM') {
                when { expression { ! skipStages.scm } }
                steps {

                    withEnv(["PATH+=${tool 'repo'}"]) {
                        dir(args.aospDir) {
                            sh """
                                [ -d .repo ] && exit 0
                                repo init -u "${args.manifestUrl}" ${args.repoBranch}
                            """

                            retry(5) {
                                sh "repo sync -j${args.jobCpus} 1>'${LOG_DIR}/repo.log' 2>&1"
                            }
                        }
                    }
                }
            }
            stage('Build') {
                when { expression { ! skipStages.build } }
                steps {
                    script {
                        aosp {
                            if (args.ccacheEnabled) {
                                sh "ccache -M ${args.ccacheSize}"
                            }

                            sh "make showcommands -j${args.jobCpus}"
                            sh "make showcommands -j${args.jobCpus} cts"

                            if (args.ctsTests.size() > 0) {
                                echo "Building CTS: ${it}"
                                args.ctsTests.each {
                                    sh "make showcommands -j${args.jobCpus} ${it}"
                                }
                            }
                        }
                    }
                }
            }
            stage('Static Analysis') {
                when { expression { ! skipStages.statictests } }
                steps {
                    parallel(
                        WarningsCheck: {
                            warnings (
                                canComputeNew: true,
                                canResolveRelativePaths: true,
                                consoleParsers: [
                                    [parserName: 'Clang (LLVM based)'],
                                    [parserName: 'GNU Make + GNU C Compiler (gcc)'],
                                    [parserName: 'Java Compiler (javac)'],
                                    [parserName: 'JavaDoc Tool']
                                ],
                                categoriesPattern: '',
                                defaultEncoding: '',
                                excludePattern: '',
                                healthy: '',
                                includePattern: '',
                                messagesPattern: '',
                                unHealthy: ''
                            )
                        }
                    )
                }
            }
            stage('CTS') {
                when { expression { ! skipStages.cts } }
                steps {
                    /*
                     * Caution: Only one Android emulator process can run on the slave.
                     * Waiting-for-boot step is waiting for the OS to completely boot
                     * on a a given emulator. If it is already done for a second
                     * process then the stage will run as long as that process is
                     * running.
                     */
                    script {
                        def android_serial = ""
                        if (args.emulatorEnabled) {
                            aosp {
                                sh "adb start-server"
                                withEnv(['JENKINS_NODE_COOKIE=dontkill']) {
                                    sh("""
                                        { nohup emulator ${args.emulatorOpts} & } \
                                          > "${LOG_EMULATOR}" 2>"${ERR_EMULATOR}"
                                        echo "\$!" >${WORKSPACE}/emulator.pid
                                    """)
                                }
                            }

                            emulatorPid = sh(script: "cat emulator.pid",
                                             returnStdout: true).trim()
                            sleep 3
                            sh "ps -p '${emulatorPid}'"
                            aosp {
                                sh "adb devices"
                                sh """
                                    adb devices | tail -n +2 | \
                                    awk '{print \$1}' >${WORKSPACE}/devices.txt
                                """
                            }
                            /*
                             * `ANDROID_SERIAL` represents the serial number
                             * to connect with. It is required for
                             * *cts-tradefed* command to target the correct
                             * device.
                             */
                            android_serial = sh(script: 'cat devices.txt',
                                                returnStdout: true).trim()
                            android_serial = "export ANDROID_SERIAL=${android_serial}"

                            echo "Waiting for OS to completely boot..."
                            aosp {
                                timeout(5) {
                                    sh "adb -e wait-for-device shell getprop init.svc.bootanim"
                                }
                            }

                            if (args.logcatEnabled) {
                                echo "Clear and capture logcat"
                                aosp {
                                    sh "adb logcat -c"
                                    withEnv(['JENKINS_NODE_COOKIE=dontkill']) {
                                        sh("""{ nohup adb logcat & } \
                                                > "${LOG_LOGCAT}" 2>"${ERR_LOGCAT}"
                                            echo "\$!" > ${WORKSPACE}/logcat.pid
                                        """)
                                    }
                                }
                                logcatPid = sh(script: "ps -p ${logcatPid}",
                                               returnStdout: true).trim()
                                sleep 3
                                sh "ps -p '${logcatPid}'"
                            }
                        } /* EMULATOR */

                        if (args.ctsTests.size() > 0) {
                            aosp {
                                args.ctsTests.each {
                                    echo "Running CTS: ${it}"
                                    sh """
                                        ${android_serial}
                                        cts-tradefed run commandAndExit cts -m ${it}
                                    """
                                }
                            }
                            store_cts_results()
                            archiveArtifacts "cts/build-${BUILD_NUMBER}/results/*.zip"
                            publishHTML target: [
                                allowMissing: false,
                                alwaysLinkToLastBuild: false,
                                keepAll: true,
                                reportDir: "cts/build-${BUILD_NUMBER}/results",
                                reportFiles: "./*/test_result.xml",
                                reportName: 'CTS Results'
                            ]
                        }
                    } /* SCRIPT */
                } /* STEPS */
            }
            stage('Sonarqube') {
                when { expression { ! skipStages.sonarqube } }
                steps {
                    script {
                        /*
                         * Normally downloaded during the first stage, that
                         * stage needs the `sonar-project.properties` file
                         * located at https://git.smile.fr/thven/pocm-ci.
                         */
                        withSonarQubeEnv('SonarServer') {
                            withEnv(["PATH+=${tool 'SonarScanner'}/bin"]) {
                                sh "sonar-scanner -D project.settings=\"${args.sonarSettings}\" "
                            }
                        }
                    }
                }
            }
        } /* END OF STAGES */
        post {
            always {
                // TODO: Save all logs and ci resulting files
                script {
                    if (logcatPid) {
                        echo "Killing logcat..."
                        kill(logcatPid)
                    }
                    if (emulatorPid) {
                        echo "Killing emulator..."
                        kill(emulatorPid)
                    }
                }
                echo "Log files:"
                sh "ls ${LOG_DIR}"
            }
            failure {
                script {
                    if (args.mailTo) {
                        mail (
                            to: args.mailTo,
                            cc: args.mailCc,
                            subject: "[${JOB_NAME}:${BUILD_NUMBER}] - failure",
                            body: "Build URL: ${BUILD_URL}"
                        )
                    }
                }
            }
        } /* END OF STAGES */
    } /* END OF PIPELINE */
}

def tool(String type) {
    if (type == "repo") {
        sh """ mkdir -p "${WORKSPACE}/bin" """
        sh """ curl https://storage.googleapis.com/git-repo-downloads/repo > "${WORKSPACE}/bin/repo" """
        sh """ chmod a+x "${WORKSPACE}/bin/repo" """
        return "${WORKSPACE}/bin"
    }
    return steps.tool(type)
}

def store_cts_results() {
    sh "mkdir -p ${WORKSPACE}/cts/build-${BUILD_NUMBER}"
    aosp {
        sh """ mv "\${ANDROID_HOST_OUT}/cts/android-cts/results" \
                  "${WORKSPACE}/cts/build-${BUILD_NUMBER}"
        """
    }
}

def kill(String pid) {
    sh "ps -p ${pid} && kill -15 ${pid}"
    sleep 30
    script {
        def rc = sh script: "ps -p ${pid} || exit 0", returnStatus: true
        if (rc != 0) {
            sh "kill -9 ${pid}"
            sleep 2
            sh "ps -p ${pid} && exit 1"
        }
    }
}
