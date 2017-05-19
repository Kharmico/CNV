JAVAC = javac
JAVA = java
JFLAGS = -d
all:
	$(JAVAC) $(JFLAGS) bin2 src/raytracer/*.java src/raytracer/pigments/*.java src/raytracer/shapes/*.java
	$(JAVAC) $(JFLAGS) bin -classpath bin:third-party:. src/InstrumentationTool.java src/BIT/highBIT/*.java src/BIT/lowBIT/*.java
	mkdir bin/raytracer
	mkdir bin/raytracer/pigments
	mkdir bin/raytracer/shapes
	$(JAVA) -cp "bin:third-party:third-party/*" InstrumentationTool bin2/raytracer/RayTracer.class bin/raytracer/RayTracer.class

	rsync -av bin2/* bin/ --exclude RayTracer.class
	
	$(JAVAC) $(JFLAGS) bin -classpath bin:third-party:. src/WebServer.java
	$(JAVA) -cp "bin:third-party:third-party/*" -XX:-UseSplitVerifier WebServer

clean:
	rm -rf bin/raytracer bin2/raytracer bin/BIT bin/*.class
