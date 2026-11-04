workflow {
    main:
    if (TEST(params.input)) {
        intermed = 1
    } else {
        intermed = 2
    }
    publish:
    result = APPLY(intermed)
}

process TEST {
    input:
    val x

    script:
    true
}

process APPLY {
    input:
    val x

    script:
    true
}
