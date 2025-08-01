compile:
	(cd ../nextflow ; ./gradlew publishToMavenLocal)
	./gradlew shadowJar

test:
	./gradlew test

install:
	./gradlew build publishToMavenLocal

clean:
	./gradlew clean
