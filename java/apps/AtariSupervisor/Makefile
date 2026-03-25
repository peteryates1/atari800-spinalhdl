# AtariSupervisor JOP application build
#
# Builds against jop-spinalhdl submodule (runtime, tools, libs).
# All Atari-specific code lives here in atari800-spinalhdl.

JDK6_HOME ?= /opt/jdk1.6.0_45
JAVAC6 := $(JDK6_HOME)/bin/javac
JAVA ?= java

# jop-spinalhdl/java is the root for runtime, tools, and libs
JOP_JAVA := ../../../jop-spinalhdl/java
TOOLS_DIR := $(JOP_JAVA)/tools
TARGET_DIR := $(JOP_JAVA)/runtime
LIB_DIR := $(JOP_JAVA)/lib

APP_PKG := atari
APP_NAME := AtariSupervisor
APP_SRC := src

BUILD_DIR := build
CLASSES_DIR := $(BUILD_DIR)/classes
PP_DIR := $(BUILD_DIR)/pp
PP_CLASSES_DIR := $(PP_DIR)/classes
JOP_OUT := $(APP_NAME).jop

# Classpath for tools
LIB_CP := $(LIB_DIR)/bcel-5.2.jar:$(LIB_DIR)/jakarta-regexp-1.3.jar:$(LIB_DIR)/log4j-1.2.15.jar:$(LIB_DIR)/jgrapht-jdk1.5.jar
TOOLS_CP := $(TOOLS_DIR)/dist/jopizer.jar:$(LIB_CP)

.PHONY: all classes preprocess jop clean

all: jop

classes:
	mkdir -p $(CLASSES_DIR) $(BUILD_DIR)
	find $(APP_SRC) $(TARGET_DIR)/src/jop $(TARGET_DIR)/src/jvm $(TARGET_DIR)/src/jdk -name '*.java' > $(BUILD_DIR)/sources.txt
	$(JAVAC6) -source 1.6 -target 1.6 -d $(CLASSES_DIR) @$(BUILD_DIR)/sources.txt

preprocess: classes
	mkdir -p $(PP_DIR)
	$(JAVA) -classpath $(TOOLS_CP) \
		com.jopdesign.build.PreLinker \
		-c $(CLASSES_DIR) -o $(PP_DIR) $(APP_PKG)/$(APP_NAME)

jop: preprocess
	$(JAVA) -Dmgci=false -classpath $(TOOLS_CP) \
		com.jopdesign.build.JOPizer \
		-cp $(PP_CLASSES_DIR) -o $(JOP_OUT) $(APP_PKG).$(APP_NAME)

clean:
	rm -rf $(BUILD_DIR) $(JOP_OUT)
