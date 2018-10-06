AOSP Wrapper
============

*"The purpose is to enhance the Declarative Pipeline DSL of Jenkins"*

From pipeline steps, it enables to go to the AOSP sources directory and for
each use of the Jenkins's `sh` builtin, it loads the project environment
variables before running the given Shell command.

It aims to do it elgantly.

# What is the enhancement ?

* Only using the `sh` builtin:

```groovy
sh """
    cd "${env.AOSP_ROOT}" && \
    source build/envsetup.sh && lunch "${env.AOSP_TARGET_BUILD}" && \
    echo "\$ANDROID_HOST_OUT"
"""
```

* Using the `dir` builtin:

```groovy
dir(env.AOSP_ROOT) {
    sh """
        source build/envsetup.sh && lunch ${env.AOSP_TARGET_BUILD} && \
        echo "\$ANDROID_HOST_OUT"
    """
}
```

* Using the wrapper:

```groovy
aosp {
    sh 'echo "$ANDROID_HOST_OUT"'
}
```

# How to use it into in pipelines ?

```groovy
    pipeline {
        environment {
            AOSP_ROOT = "aosp"
            AOSP_TARGET_BUILD = "aosp_x86_64-eng"
        }

        stages {
            stage("build") {
                steps {
                    aosp {
                        sh 'echo "$ANDROID_HOST_OUT"'
                    }
                }
            }
        }
    }
```
