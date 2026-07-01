SHELL := /bin/bash

REPO ?= .
SBT ?= sbt
JAR_NAME ?= eqwalizer-strict.jar

.DEFAULT_GOAL := build

.PHONY: build
build: jar

.PHONY: compile
compile:
	cd "$(REPO)/eqwalizer" && $(SBT) compile

.PHONY: jar
jar:
	cd "$(REPO)/eqwalizer" && $(SBT) assembly

.PHONY: jar-path
jar-path:
	@find "$(REPO)/eqwalizer/target" -name "$(JAR_NAME)" -print

.PHONY: status
status:
	cd "$(REPO)" && git status --short && echo && git diff --stat

.PHONY: clean
clean:
	cd "$(REPO)/eqwalizer" && $(SBT) clean

.PHONY: rebuild
rebuild: clean build
