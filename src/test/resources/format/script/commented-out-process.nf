// process FOO {
//     script:
//     "true"
// }

process BAR {
    script:
    "true"
}

workflow {
    x=BAR()
}
