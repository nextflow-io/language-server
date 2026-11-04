workflow {
    main:
    ch_versions = channel.empty()

    FOO()
    ch_versions = ch_versions.mix( FOO.out.versions )

    if (params.bar) {
        BAR()
        ch_versions = ch_versions.mix( BAR.out.versions )
    }

    publish:
    versions = ch_versions
}

process FOO {
    output:
    val 'versions', emit: versions

    script:
    true
}

process BAR {
    output:
    val 'versions', emit: versions

    script:
    true
}
