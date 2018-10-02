#!/usr/bin/groovy

library 'aosp-pipeline'

withEnv([ "NODE_LABEL=<node-label>",
          "JAVA_HOME=/usr/lib/jvm/java-1.8.0-openjdk-amd64" ]) {
    aospPipeline {
        manifestUrl     = '<git@git.domain.com:user/repository-name>'
        repoBranch      = '<repo-branch-name>'
        targetProduct   = 'aosp_x86_64'
        buildVariant    = 'eng'
        ccacheEnabled   = true
        ccacheSize      = '50G'
        jobCpus         = 4
        logcatEnabled   = false
        emulatorEnabled = true
        emulatorOpts    = [
            "-memory 1024",
            "-accel on",
            "-no-snapshot",
            "-timezone Europe/Paris"
        ]
        ctsTests        = [ "<test_1>", "<test_2>" ]
        skipStages      = skip
        mailTo          = "<firstname.lastname@domain.com>"
    }
}
