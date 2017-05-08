JAVAC = javac
JAVA = java
JFLAGS = -d
all:
	$(JAVAC) $(JFLAGS) bin2 src/raytracer/*.java src/raytracer/pigments/*.java src/raytracer/shapes/*.java
	$(JAVAC) $(JFLAGS) bin src/InstrumentationTool.java src/BIT/highBIT/*.java src/BIT/lowBIT/*.java
	mkdir bin/raytracer
	mkdir bin/raytracer/pigments
	mkdir bin/raytracer/shapes
	$(JAVA) -cp bin InstrumentationTool bin2/raytracer bin/raytracer

	cp bin2/raytracer/pigments/* bin/raytracer/pigments
	cp bin2/raytracer/shapes/* bin/raytracer/shapes
	cp info/*.txt bin/
	cp info/*.bmp bin/
	
	$(JAVAC) $(JFLAGS) bin -classpath bin:. src/WebServer.java
	$(JAVA) -cp bin -XX:-UseSplitVerifier WebServer

clean:
	rm -rf bin/* bin2/*