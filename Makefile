compile:
	(cd ../nextflow ; ./gradlew :nf-lang:publishToMavenLocal)
	./gradlew shadowJar

test:
	./gradlew test

install:
	./gradlew build publishToMavenLocal

clean:
	./gradlew clean
