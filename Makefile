compile:
	(cd ../nextflow ; ./gradlew :nf-lang:publishToMavenLocal)
	./gradlew build

test:
	./gradlew test

install:
	./gradlew build publishToMavenLocal

clean:
	./gradlew clean
