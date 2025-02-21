compile:
	./gradlew build

test:
	./gradlew test

install:
	./gradlew build publishToMavenLocal

clean:
	./gradlew clean
