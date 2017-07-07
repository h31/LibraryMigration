# LibraryMigration
A tool that helps to migrate (port) code from one library to another.

A more verbose description is available in [the paper](http://www.system-informatics.ru/en/article/143).

## Setup Instruction

LibraryMigration requires the Java Development Kit (JDK) version 8 or later. JRE is not sufficient. For example, on Windows you can use [Oracle JDK](http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html). On Ubuntu/Debian, you can use `openjdk-8-jdk` package to run the LibraryMigration project.

1. Run `git clone https://github.com/h31/LibraryMigration.git` command or download and unpack a [ZIP archive](https://github.com/h31/LibraryMigration/archive/master.zip).
2. If you are running Linux and have used git to fetch the source, it is strongly recommended to execute `examples/make_traces.sh` script.
3. Run the tests:
    * Linux: `./gradlew clean test`
    * Windows (cmd.exe): `gradlew.bat clean test`
    * Windows (PowerShell): `.\gradlew clean test`

Original code can be found in `examples` directory. Migrated code is located in the `examples/migrated` directory.
