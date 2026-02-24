plugins {
    kotlin("jvm") version "2.2.21"
    `java-library`                          // api() 구성 사용을 위해 필요
    id("com.google.protobuf") version "0.9.4"
}

group = "org.example"
version = "unspecified"

repositories {
    mavenCentral()
    maven { url = uri("https://packages.confluent.io/maven/") }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(24)
    }
}

dependencies {
    implementation(kotlin("stdlib"))

    // Protobuf - api로 선언해야 소비자 서비스(order, inventory 등)에 transitive하게 전파됨
    api("com.google.protobuf:protobuf-kotlin:3.25.3")
    api("com.google.protobuf:protobuf-java:3.25.3")

    // Schema Registry (Protobuf 직렬화 타입 공유용)
    compileOnly("io.confluent:kafka-protobuf-serializer:7.8.0")

    testImplementation(kotlin("test"))
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.3"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("kotlin")
            }
        }
    }
}

// proto 생성 소스를 Kotlin 컴파일 경로에 포함
sourceSets {
    main {
        kotlin.srcDirs(
            "src/main/kotlin",
            "build/generated/source/proto/main/kotlin",
            "build/generated/source/proto/main/java"
        )
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.register("prepareKotlinBuildScriptModel") { }
