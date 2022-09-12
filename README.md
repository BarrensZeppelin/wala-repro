# Setup

Point the `JAVA_HOME` environment variable to an installation of OpenJDK 11.

Run `./setup.sh` to clone and build the `vert.x` project in a specific version.

Run the reproducer with 

```bash
MAVEN_OPTS="-Xmx16g" mvn compile exec:java -Dexec.mainClass=dk.casa.wala.reproducer.App
```

which will run some points-to queries with the WALA on-demand analysis. 