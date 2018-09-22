#!/usr/bin/groovy

def call(Closure body)
{
    call(env.AOSP_ROOT, body)
}

def call(String path, Closure body)
{
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = this

    dir(path) {
        body()
    }
}

def sh(Map params = [:])
{
    String script = params.script
    Boolean returnStatus = params.get('returnStatus', false)
    Boolean returnStdout = params.get('returnStdout', false)
    String encoding = params.get('encoding', null)
    Boolean lunch = params.get('lunch', true)

    if (lunch) {
        script = """
        set +x
        { . build/envsetup.sh && lunch ${env.AOSP_TARGET_BUILD}; } || exit 1
        set -x
        ${script}
        """
    }

    return steps.sh(script: script,
                    returnStatus: returnStatus,
                    returnStdout: returnStdout,
                    encoding: encoding)
}

def sh(String script)
{
    return sh(script: script)
}
