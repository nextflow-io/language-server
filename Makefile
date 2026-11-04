compile:
	(cd ../nextflow ; ./gradlew :nf-lang:publishToMavenLocal)
	./gradlew shadowJar

test:
ifndef class
	./gradlew test
else
	./gradlew test --tests ${class}
endif

install:
ifndef VERSION
	$(error VERSION is required, e.g. `VERSION=<version> make install`)
endif
	./gradlew bundle -Pversion=$(VERSION)
	scripts/install.sh --version $(VERSION)

.PHONY: bundle bundle-all
bundle:
	./gradlew bundle

bundle-all:
	./gradlew bundleAll

.PHONY: bench bench-compare
bench:
	./gradlew shadowJar
	scripts/bench-workspace.sh
	python3 scripts/bench.py --workspace build/bench/rnaseq

bench-compare:
	scripts/bench-workspace.sh
	scripts/bench-compare.sh --base main

clean:
	./gradlew clean
