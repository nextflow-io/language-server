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
	./gradlew build publishToMavenLocal

clean:
	./gradlew clean
