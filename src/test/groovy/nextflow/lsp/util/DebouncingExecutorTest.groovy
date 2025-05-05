/*
 * Copyright 2024-2025, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nextflow.lsp.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import spock.lang.Specification

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class DebouncingExecutorSpec extends Specification {

    def "debouncer delays and deduplicates rapid submissions"() {
        given:
        def counter = 0
        def latch = new CountDownLatch(1)
        def debouncer = new DebouncingExecutor(200, {
            counter++
            latch.countDown()
        })

        when: "executeLater is called rapidly multiple times"
        debouncer.executeLater()
        Thread.sleep(50)
        debouncer.executeLater()
        Thread.sleep(50)
        debouncer.executeLater()

        then: "action is only run once after the last delay"
        latch.await(1, TimeUnit.SECONDS)
        counter == 1

        cleanup:
        debouncer.shutdown()
    }

    def "executeNow runs the action immediately and cancels pending task"() {
        given:
        def executed = false
        def latch = new CountDownLatch(1)
        def debouncer = new DebouncingExecutor(300, {
            executed = true
            latch.countDown()
        })

        when:
        debouncer.executeLater()
        Thread.sleep(100)
        debouncer.executeNow()

        then: "action runs immediately"
        latch.await(500, TimeUnit.MILLISECONDS)
        executed

        cleanup:
        debouncer.shutdown()
    }

    def "multiple executeNow calls run the action each time"() {
        given:
        def executions = 0
        def debouncer = new DebouncingExecutor(100, { executions++ })

        when:
        debouncer.executeNow()
        debouncer.executeNow()
        debouncer.executeNow()

        then:
        executions == 3

        cleanup:
        debouncer.shutdown()
    }
}
