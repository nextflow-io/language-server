package nextflow.script.dsl

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

interface DslScope {
}

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@interface Constant {
    String value()
}

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface Function {
    String value()
}
