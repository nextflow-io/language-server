workflow {
    main:
    if( params.echo )
      echo = 1
    else
      echo = 0

    publish:
    echo = echo
}
