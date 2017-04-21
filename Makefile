JAVAC = javac
JAVA = java
JFLAGS = -d
all:
	$(JAVAC) $(JFLAGS) bin src/raytracer/*.java src/raytracer/pigments/*.java src/raytracer/shapes/*.java src/WebServer.java
	$(JAVAC) $(JFLAGS) bin src/InstrumentationTool.java src/BIT/highBIT/*.java src/BIT/lowBIT/*.java
	$(JAVA) -cp bin InstrumentationTool bin/raytracer bin/raytracer

	cp info/*.txt bin/
	cp info/*.bmp bin/

	$(JAVA) -cp bin -XX:-UseSplitVerifier WebServer

clean:
