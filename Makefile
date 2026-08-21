compile:
	(cd ../nextflow ; ./gradlew publishToMavenLocal)
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
	./gradlew shadowJar
	$(eval STABLE := v$(shell echo $(VERSION) | cut -d. -f1-2))
	@mkdir -p ~/.nextflow/lsp/$(STABLE)
	@cp build/libs/language-server-all.jar ~/.nextflow/lsp/$(STABLE)/v$(VERSION).jar
	@echo "installed at: ~/.nextflow/lsp/$(STABLE)/v$(VERSION).jar"

.PHONY: native
native:
	./native/build.sh

install-native:
ifndef VERSION
	$(error VERSION is required, e.g. `VERSION=<version> make install-native`)
endif
	$(eval STABLE := v$(shell echo $(VERSION)))
	@mkdir -p ~/.nextflow/lsp/$(STABLE)
	@cp build/native/nativeCompile/nextflow-lsp ~/.nextflow/lsp/$(STABLE)
	@echo "installed at: ~/.nextflow/lsp/$(STABLE)/nextflow-lsp"

clean:
	./gradlew clean
