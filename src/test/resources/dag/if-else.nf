workflow {
    if (params.echo) {
        echo = TOUCH(params.echo)
    } else {
        echo = DEFAULT()
    }
    APPEND(echo)
}

process TOUCH {
    input:
    val x

    script:
    true
}

process DEFAULT {
    true
}

process APPEND {
    input:
    val x

    script:
    true
}
