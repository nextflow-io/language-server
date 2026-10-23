workflow {
    main:
    echo = params.echo ? 1 : 0

    publish:
    echo = echo
}
