JAVAC = javac
JAVA = java
JAR = jar
MAIN_SOURCES := $(wildcard src/main/java/music/*.java)
TEST_SOURCES := $(wildcard src/test/java/music/*Test.java)
TEST_CLASSES := $(patsubst src/test/java/music/%.java,music.%,$(TEST_SOURCES))
ifeq ($(origin REVISION), undefined)
REVISION := $(shell sh scripts/source-revision.sh)
endif
export REVISION
ARTIFACT := build/music-recommender.jar

.PHONY: build test clean

build:
	mkdir -p build
	rm -rf build/classes
	mkdir -p build/classes
	$(JAVAC) --release 17 -encoding UTF-8 -d build/classes $(MAIN_SOURCES)
	printf 'Implementation-Version: %s\n' "$$REVISION" > build/manifest.mf
	$(JAR) --create --date=2000-01-01T00:00:00Z --manifest build/manifest.mf --file $(ARTIFACT) --main-class music.Main -C build/classes .

test: build
	rm -rf build/test-classes
	mkdir -p build/test-classes
	$(JAVAC) --release 17 -encoding UTF-8 -cp build/classes -d build/test-classes $(TEST_SOURCES)
	@set -e; for test in $(TEST_CLASSES); do \
		echo "Running $$test"; \
		$(JAVA) -cp build/classes:build/test-classes $$test; \
	done
	@set -eu; dir=$$(mktemp -d); trap 'rmdir "$$dir"' EXIT; \
		version=$$(cd "$$dir" && "$(JAVA)" -jar "$(abspath $(ARTIFACT))" --version); \
		test "$$version" = "$$REVISION"
	@$(JAVA) -jar $(ARTIFACT) recommend maya --limit 3 > /dev/null
	@$(JAVA) -jar $(ARTIFACT) evaluate --limit 3 > /dev/null
	@bash src/test/sh/feedback-write-failure.sh "$(JAVA)" "$(ARTIFACT)"
	@bash src/test/sh/packaged-readiness.sh "$(JAVA)" "$(ARTIFACT)"
	@bash src/test/sh/reproducible-jar.sh "$(MAKE)" "$(JAVAC)" "$(JAR)" "$(ARTIFACT)"
	@if command -v git >/dev/null; then bash src/test/sh/source-revision.sh; fi

clean:
	rm -rf build
