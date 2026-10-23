workflow {
    echo = params.echo
        ? TOUCH(params.echo)
        : DEFAULT()
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
