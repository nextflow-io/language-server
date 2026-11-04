workflow ECHO {
    take:
    debug

    main:
    if( debug )
        echo = 1
    else
        echo = 0

    emit:
    echo = echo
}
