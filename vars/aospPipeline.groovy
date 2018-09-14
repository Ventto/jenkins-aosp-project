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
 * - doClean         | Boolean      | false
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
 * - emulator    | Boolean | If true, skips 'Emulator' step
 * - unittests   | Boolean | If true, skips 'Unit Testing' step
 * - statictests | Boolean | If true, skips 'Static Analysis' step
 * - sonar       | Boolean | If true, skips 'Sonar' step
 * - artefact    | Boolean | If true, skips 'Artefacts' step
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
    args.doClean = (args.doClean) ? args.doClean : 0
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
        emulator     : false,
        unittests    : false,
        statictests  : false,
        sonarqube    : false,
        artefacts    : false,
    ]

    skipStages = (args.skipStages) ? args.skipStages : skipStages

    /* Set the `USE_CCACHE` environment variable to enable building cache */
    def use_ccache = (args.ccacheEnabled) ? 1 : 0

    // ============================
    //          Variables
    // ============================

    def lastStageName = ""

    // TODO: Call the following shell command line before each sh step
    def SETENV       = """
        set +x
        { . build/envsetup.sh && \
          lunch ${args.targetProduct}-${args.buildVariant}
        } || exit 1
        set -x
    """

    // The ccache binary is either for `linux-86` or `darwin-x86`
    def CCACHE_BIN = "prebuilts/misc/linux-x86/ccache/ccache"

    /*
     * The `ANDROID_HOST_OUT` variable is getting a value after
     * running "${SETENV}" Shell script before using ${ADB_BIN}.
     */
    def ADB_BIN = "\${ANDROID_HOST_OUT}/bin/adb"

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
            LOG_DIR      = "${WORKSPACE}/logs/${JOB_NAME}-${BUILD_NUMBER}"
            LOG_EMULATOR = "${LOG_DIR}/emulator.log"
            ERR_EMULATOR = "${LOG_DIR}/emulator-err.log"
            LOG_LOGCAT   = "${LOG_DIR}/logcat.log"
            ERR_LOGCAT   = "${LOG_DIR}/logcat-err.log"
            /*
             * Android Environment Variables:
             *
             * - `ANDROID_SERIAL` represents the serial number to connect with
             *   `adb` (without using -s option that overrides that variable).
             */
            ANDROID_SERIAL       = "emulator-${args.emulatorPort}"
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
                    script { if (args.doClean) { cleanWs() } }
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
                        if (args.ccacheEnabled) {
                            dir(args.aospDir) {
                                sh "${CCACHE_BIN} -M ${args.ccacheSize}"
                            }
                        }
                    }
                    script {
                        dir(args.aospDir) {
                            echo "Building AOSP"
                            sh "${SETENV} make showcommands -j${args.jobCpus}"

                            echo "Building CTS"
                            sh "${SETENV} make showcommands -j${args.jobCpus} cts"

                            if (args.ctsTests.size() > 0) {
                                echo "Building CTS: ${it}"
                                args.ctsTests.each {
                                    sh "${SETENV} make showcommands -j${args.jobCpus} ${it}"
                                }
                            }
                        }
                    }
                }
            }
            stage('Unit Tests') {
                when { expression { ! skipStages.unittests } }
                steps {
                    /*
                     * FIXME: Only one Android emulator process can run on the slave.
                     * Waiting-for-boot step is waiting for the OS to completely boot
                     * on a a given emulator. If it is already done for a second
                     * process then the stage will run as long as that process is
                     * running.
                     */
                    script {
                        if (args.emulatorEnabled) {
                            echo "Starting emulator..."

                            dir(args.aospDir) {
                                withEnv(['JENKINS_NODE_COOKIE=dontkill']) {
                                    emulatorPid = steps.sh(returnStdout: true, script: """
                                    { ${SETENV}
                                      nohup emulator ${args.emulatorOpts} &
                                    } > "${LOG_EMULATOR}" 2>"${ERR_EMULATOR}"
                                    echo "\$!" """).trim()
                                }

                                sh "ps -p '${emulatorPid}'"

                                echo "Waiting for OS to completely boot..."

                                sh """
                                    ${SETENV}
                                    CMD="${ADB_BIN} wait-for-device \
                                                shell getprop init.svc.bootanim"
                                    until \$CMD | grep -m 1 stopped; do sleep 2; done
                                """

                                echo "Clear and capture logcat"

                                if (args.logcatEnabled) {
                                    sh "${SETENV} ${ADB_BIN} logcat -c"
                                    logcatPid = sh(returnStdout: true, script: """
                                        { ${SETENV}
                                          nohup ${ADB_BIN} logcat & } \
                                            > "${LOG_LOGCAT}" 2>"${ERR_LOGCAT}" &
                                        echo "\$!"
                                    """).trim()
                                    sh "ps -p ${logcatPid}"
                                }
                            }
                        } /* EMULATOR */
                        dir(args.aospDir) {
                            if (args.ctsTests.size() > 0) {
                                args.ctsTests.each {
                                    echo "Running CTS: ${it}"
                                    sh """
                                        ${SETENV}
                                        cts-tradefed run commandAndExit cts -m ${it}
                                    """
                                }
                            }
                        }
                    } /* SCRIPT */
                } /* STEPS */
            }
            stage('Static Analysis') {
                when { expression { ! skipStages.statictests } }
                steps {
                    parallel(
                        WarningsCheck: {
                            warnings (
                                canComputeNew: true,
                                canResolveRelativePaths: true,
                                categoriesPattern: '',
                                consoleParsers: [
                                    [parserName: 'GNU C Compiler 4 (gcc)'],
                                    [parserName: 'Clang (LLVM based)'],
                                    [parserName: 'GNU Make + GNU C Compiler (gcc)'],
                                    [parserName: 'Java Compiler (javac)'],
                                    [parserName: 'JavaDoc Tool']
                                ],
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
            stage('Sonarqube') {
                when { expression { ! skipStages.sonarqube } }
                steps {
                    echo 'Sonar !'
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
                        sh """
                            if ps -p "${logcatPid}" >/dev/null 2>&1; then
                                kill "${logcatPid}"
                            fi
                        """
                    } else {
                        /* Do nothing */
                    }
                    if (emulatorPid) {
                        echo "Killing emulator..."
                        sh """
                            if ps -p "${emulatorPid}" >/dev/null 2>&1; then
                                kill "${emulatorPid}"
                            fi
                        """
                    } else {
                        /* Do nothing */
                    }
                }
                echo "Log files:"
                sh "ls ${LOG_DIR}"
            }
            failure {
                echo "Post Stages: failure"
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
    // FIXME: Implement *repo* installer
    if (type == "repo") {
        sh """ mkdir -p "${WORKSPACE}/bin" """
        sh """ curl https://storage.googleapis.com/git-repo-downloads/repo > "${WORKSPACE}/bin/repo" """
        sh """ chmod a+x "${WORKSPACE}/bin/repo" """
        return "${WORKSPACE}/bin"
    }
    return steps.tool(type)
}
